package cn.railway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static cn.railway.provider.RuntimeObservation.Report;

/** Nonblocking submission. One background worker owns persistence and delivery; retries keep exact report IDs/content. */
@Component
public class RuntimeTelemetryReporter implements AutoCloseable {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Status(boolean enabled,boolean storageReady,int queuedEvents,int stagedReports,int pendingReports,long quarantinedReports,long submittedEvents,
                         long droppedEvents,long spoolFailures,long deliveryFailures,long rejectedReports,long deliveredReports,
                         Instant lastSuccessAt,String lastFailureCode) {}
    private record Item(String point,RuntimeObservation event) {}
    private record Window(String point,Instant start) {}
    private record Pending(Report report,Path file) {}
    private final boolean enabled;
    private final ArrayBlockingQueue<Item> queue;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String control;
    private final Path spool;
    private final int maxPending;
    private final ScheduledExecutorService worker;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Deque<Report> staged=new ArrayDeque<>();
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private final AtomicLong submitted=new AtomicLong(),dropped=new AtomicLong(),gapThrough=new AtomicLong(Long.MIN_VALUE);
    private volatile int stagedSize,pendingSize;
    private volatile long spoolFailures,deliveryFailures,rejected,delivered,quarantined;
    private volatile Instant lastSuccess;
    private volatile String failure;
    private volatile boolean closing;
    private volatile boolean loaded;
    private Instant nextWindow=Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
    private long nextAttemptNanos;
    private int consecutiveFailures;
    public RuntimeTelemetryReporter(Environment env,ObjectMapper json,CapabilityRuntimeGuard runtime) {
        this.json=json;enabled=env.getProperty("PROVIDER_TELEMETRY_ENABLED",Boolean.class,true);
        int capacity=setting(env,"PROVIDER_TELEMETRY_QUEUE_CAPACITY",1000,1,10000);
        maxPending=setting(env,"PROVIDER_TELEMETRY_MAX_PENDING_REPORTS",1000,1,10000);
        queue=new ArrayBlockingQueue<>(capacity);
        spool=Path.of(env.getProperty("PROVIDER_TELEMETRY_SPOOL_DIR",".runtime/local/telemetry")).toAbsolutePath().normalize();
        control=env.getProperty("PROVIDER_CONTROL_TOKEN","");
        URI base=runtime.foundationBase();String configured=env.getProperty("PROVIDER_TELEMETRY_BASE_URL","");
        if(!configured.isBlank())base=URI.create(configured);
        if(base.getHost()==null||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null
                ||!(base.getRawPath().isEmpty()||base.getRawPath().equals("/"))||!Set.of("http","https").contains(base.getScheme()))throw new IllegalStateException("运行摘要上报地址配置不合法");
        endpoint=base.resolve("/api/v1/telemetry/reports");
        worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"provider-runtime-reporter");t.setDaemon(true);return t;});
        if(enabled)worker.scheduleWithFixedDelay(this::tick,0,250,TimeUnit.MILLISECONDS);
    }
    public boolean submit(String point,RuntimeObservation observation) {
        if(!enabled||closing)return false;
        if(!Set.of("REST_PROVIDER","KAFKA_PRODUCER").contains(point))throw new IllegalArgumentException("采集位置不合法");
        submitted.incrementAndGet();
        if(queue.offer(new Item(point,observation)))return true;
        dropped.incrementAndGet();gap(observation.completedAt().truncatedTo(ChronoUnit.MINUTES));return false;
    }
    public Status status(){return new Status(enabled,loaded,queue.size(),stagedSize,pendingSize,quarantined,submitted.get(),dropped.get(),spoolFailures,deliveryFailures,rejected,delivered,lastSuccess,failure);}
    private void tick() {
        try {
            if(!loaded){recover();loaded=true;}
            stageQueue();
            persistStaged();
            // The first partial startup minute and downtime are never filled with fabricated zero reports.
            // A dropped/rejected window stays incomplete. Closing is a node declaration, not a no-loss guarantee.
            if(staged.isEmpty()&&queue.isEmpty()&&pending.size()+2<=maxPending&&nextWindow.plusSeconds(65).isBefore(Instant.now())) {
                if(nextWindow.toEpochMilli()>gapThrough.get())for(String point:List.of("REST_PROVIDER","KAFKA_PRODUCER"))
                    staged.add(new Report(UUID.randomUUID().toString(),point,nextWindow,nextWindow.plusSeconds(60),true,List.of()));
                nextWindow=nextWindow.plusSeconds(60);persistStaged();
            }
            if(!pending.isEmpty()&&System.nanoTime()>=nextAttemptNanos)deliver(pending.values().iterator().next());
        } catch(Exception e) {
            spoolFailures++;failure="SPOOL_UNAVAILABLE";
        } finally {stagedSize=staged.size();pendingSize=pending.size();}
    }
    private void stageQueue(){
        if(!staged.isEmpty()||pending.size()>=maxPending)return;
        var items=new ArrayList<Item>();queue.drainTo(items,200);
        var groups=new LinkedHashMap<Window,List<RuntimeObservation>>();
        for(var item:items)groups.computeIfAbsent(new Window(item.point(),item.event().completedAt().truncatedTo(ChronoUnit.MINUTES)),key->new ArrayList<>()).add(item.event());
        for(var group:groups.entrySet())staged.add(new Report(UUID.randomUUID().toString(),group.getKey().point(),group.getKey().start(),group.getKey().start().plusSeconds(60),false,List.copyOf(group.getValue())));
        stagedSize=staged.size();
    }
    private void persistStaged()throws Exception {
        Files.createDirectories(spool);
        while(!staged.isEmpty()&&pending.size()<maxPending) {
            var report=staged.peek();var target=spool.resolve(report.reportId()+".json");var temporary=spool.resolve(report.reportId()+".tmp");
            byte[] bytes=json.writeValueAsBytes(report);
            if(bytes.length>1048576)throw new IllegalStateException("摘要报告过大");
            Files.write(temporary,bytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,target);}
            pending.put(report.reportId(),new Pending(report,target));staged.remove();pendingSize=pending.size();stagedSize=staged.size();
        }
    }
    private void recover()throws Exception {
        Files.createDirectories(spool);
        quarantined=0;
        try(var archived=Files.newDirectoryStream(spool)) {for(var file:archived)if(file.getFileName().toString().matches("[a-f0-9-]{36}\\.json\\.(invalid|rejected)"))quarantined++;}
        var recovered=new ArrayList<Pending>();
        try(var paths=Files.newDirectoryStream(spool,"*.json")) {
            for(var path:paths) {
                if(!path.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))continue;
                if(recovered.size()>=maxPending)throw new IllegalStateException("待补报数量超出配置上限");
                try {
                    if(Files.size(path)>1048576)throw new IllegalStateException("摘要文件过大");
                    var report=json.readValue(Files.readAllBytes(path),Report.class);
                    if(!path.getFileName().toString().equals(report.reportId()+".json")||report.events()==null||report.events().size()>200
                            ||report.windowStart()==null||report.windowUntil()==null||!report.windowUntil().equals(report.windowStart().plusSeconds(60))
                            ||!Set.of("REST_PROVIDER","KAFKA_PRODUCER").contains(report.collectionPoint()))throw new IllegalStateException("摘要文件结构不合法");
                    recovered.add(new Pending(report,path));
                } catch(Exception e) {Files.move(path,path.resolveSibling(path.getFileName()+".invalid"));spoolFailures++;quarantined++;failure="SPOOL_CORRUPT";gapThrough.set(Long.MAX_VALUE);}
            }
        }
        recovered.sort(Comparator.comparing((Pending p)->p.report().windowStart()).thenComparing(p->p.report().windowClosed()));
        for(var item:recovered)pending.put(item.report().reportId(),item);
        pendingSize=pending.size();
    }
    private void deliver(Pending item) {
        try {
            if(!control.matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalStateException("摘要服务凭据不可用");
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json")
                    .header("X-Provider-Token",control).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(item.report()))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==400) {
                Files.move(item.file(),item.file().resolveSibling(item.file().getFileName()+".rejected"));
                pending.remove(item.report().reportId());pendingSize=pending.size();rejected++;quarantined++;failure="REPORT_REJECTED";gap(item.report().windowStart());backoff();return;
            }
            if(response.statusCode()!=200)throw new IllegalStateException("摘要接收暂不可用");
            var envelope=json.readTree(response.body());var data=envelope.path("data");
            if(!envelope.path("code").isInt()||envelope.path("code").intValue()!=0||!item.report().reportId().equals(data.path("report_id").asText())
                    ||!data.path("accepted_events").isInt()||!data.path("duplicate_events").isInt()||data.path("accepted_events").intValue()<0||data.path("duplicate_events").intValue()<0
                    ||(long)data.path("accepted_events").intValue()+data.path("duplicate_events").intValue()!=item.report().events().size()||!data.path("replayed").isBoolean())throw new IllegalStateException("摘要回执未确认");
            Instant accepted=Instant.parse(data.path("received_at").asText());if(accepted.isAfter(Instant.now().plusSeconds(5)))throw new IllegalStateException("摘要回执时间不合法");
            Files.delete(item.file());pending.remove(item.report().reportId());pendingSize=pending.size();delivered++;lastSuccess=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            failure=null;consecutiveFailures=0;nextAttemptNanos=0;
        } catch(InterruptedException e) {Thread.currentThread().interrupt();deliveryFailures++;failure="DELIVERY_UNCONFIRMED";backoff();}
        catch(Exception e) {deliveryFailures++;failure="DELIVERY_UNCONFIRMED";backoff();}
    }
    private void backoff(){consecutiveFailures=Math.min(6,consecutiveFailures+1);nextAttemptNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(30000,250L<<consecutiveFailures));}
    private void gap(Instant window){gapThrough.accumulateAndGet(window.toEpochMilli(),Math::max);}
    private static int setting(Environment env,String name,int fallback,int min,int max){int value=env.getProperty(name,Integer.class,fallback);if(value<min||value>max)throw new IllegalStateException("摘要队列配置不合法");return value;}
    @Override @PreDestroy public synchronized void close() {
        if(closing)return;
        closing=true;worker.shutdown();
        try{if(!worker.awaitTermination(3,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}
        // Graceful stop may persist the bounded remainder, but crash-before-persistence remains an explicit risk.
        if(enabled&&worker.isTerminated()) {
            try{
                if(!loaded){recover();loaded=true;}
                while((!queue.isEmpty()||!staged.isEmpty())&&pending.size()<maxPending){stageQueue();persistStaged();}
            }catch(Exception e){spoolFailures++;failure="SPOOL_UNAVAILABLE";}
            finally{pendingSize=pending.size();stagedSize=staged.size();}
        }
    }
}
