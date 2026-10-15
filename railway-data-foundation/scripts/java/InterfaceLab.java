import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** External, bounded transport experiment, NOT the production Provider or a Demand business service. */
public final class InterfaceLab {
    static String random(){return UUID.randomUUID().toString().replace("-","");}
    static byte[] payload(int seq,long started,int bytes){var b=ByteBuffer.allocate(bytes);b.putInt(seq).putLong(started);while(b.hasRemaining())b.put((byte)'x');return b.array();}
    static class Observation {
        long start=System.nanoTime();final List<Double> latency=Collections.synchronizedList(new ArrayList<>());
        final Set<Integer> unique=ConcurrentHashMap.newKeySet();final AtomicInteger failures=new AtomicInteger(),duplicates=new AtomicInteger();final AtomicLong bytes=new AtomicLong(),last=new AtomicLong(),maxBacklog=new AtomicLong();
        void received(byte[] b){var input=ByteBuffer.wrap(b);int seq=input.getInt();long sent=input.getLong();if(!unique.add(seq)){duplicates.incrementAndGet();return;}latency.add((System.nanoTime()-sent)/1_000_000.0);bytes.addAndGet(b.length);last.set(System.nanoTime());}
        double percentile(double p){synchronized(latency){if(latency.isEmpty())return -1;var sorted=new ArrayList<>(latency);sorted.sort(Double::compare);return sorted.get((int)Math.ceil(p*sorted.size())-1);}}
    }
    static void pace(String scenario,int seq,int count,int rate,int pauseMs)throws InterruptedException {
        if(scenario.equals("intermittent")&&seq==count/2)Thread.sleep(pauseMs);
        if(!scenario.equals("burst"))TimeUnit.NANOSECONDS.sleep(1_000_000_000L/rate);
    }
    static String row(String scenario,String transport,int count,Observation o,long offeredEnd,int pauseMs) {
        double elapsed=(o.last.get()==0?System.nanoTime():o.last.get())-o.start;
        double drain=o.last.get()==0?-1:Math.max(0,(o.last.get()-offeredEnd)/1_000_000.0);
        return String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d",scenario,transport,count,o.unique.size(),o.failures.get(),o.duplicates.get(),o.bytes.get(),elapsed/1_000_000_000.0,o.unique.size()/Math.max(.000001,elapsed/1_000_000_000.0),o.percentile(.50),o.percentile(.95),drain,o.maxBacklog.get(),pauseMs);
    }
    static String rest(String scenario,int count,int size,int rate,int pauseMs)throws Exception {
        // Isolated external source fixture: fixed-size synthetic responses, not production authorization/performance.
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var workers=Executors.newFixedThreadPool(4);server.setExecutor(workers);String token=random();
        long[] sent=new long[count];var observation=new Observation();var clients=Executors.newFixedThreadPool(4);var jobs=new ArrayList<Future<?>>();
        server.createContext("/events/",exchange->{try{
            if(!("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);return;}
            int seq=Integer.parseInt(exchange.getRequestURI().getPath().substring(8));if(seq<0||seq>=count){exchange.sendResponseHeaders(404,-1);return;}
            byte[] body=payload(seq,sent[seq],size);exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception ex){try{exchange.sendResponseHeaders(400,-1);}catch(Exception ignored){}}finally{exchange.close();}});
        server.start();var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            observation.start=System.nanoTime();
            for(int i=0;i<count;i++){
                pace(scenario,i,count,rate,pauseMs);int seq=i;sent[i]=System.nanoTime();
                jobs.add(clients.submit(()->{try{
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/events/"+seq)).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+token).GET().build();
                    var r=http.send(request,HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()!=200||r.body().length!=size)throw new IllegalStateException();observation.received(r.body());
                }catch(Exception ex){observation.failures.incrementAndGet();}}));
            }
            long end=System.nanoTime();for(var job:jobs)job.get(20,TimeUnit.SECONDS);
            return row(scenario,"REST_FIXTURE",count,observation,end,scenario.equals("intermittent")?pauseMs:0);
        }finally{clients.shutdownNow();server.stop(0);workers.shutdownNow();}
    }
    static Properties identity(Properties id,String user,String password){var p=new Properties();p.putAll(id);p.setProperty("lab.username",user);p.setProperty("lab.password",password);return LocalKafka.client(p,"lab");}
    static List<AclBinding> bindings(String writer,String reader,String topic,String group){return List.of(LocalKafka.acl(writer,ResourceType.TOPIC,topic,AclOperation.WRITE),LocalKafka.acl(writer,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),LocalKafka.acl(writer,ResourceType.CLUSTER,"kafka-cluster",AclOperation.IDEMPOTENT_WRITE),LocalKafka.acl(reader,ResourceType.TOPIC,topic,AclOperation.READ),LocalKafka.acl(reader,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),LocalKafka.acl(reader,ResourceType.GROUP,group,AclOperation.READ));}
    static String kafka(Properties id,String meta,String scenario,int count,int size,int rate,int pauseMs,Path output)throws Exception {
        String suffix=random(),topic="rdlab_"+suffix,group="rdlab_group_"+suffix,writer="rdlab_write_"+suffix,reader="rdlab_read_"+suffix,password=random(),readerPassword=random();
        var acl=bindings(writer,reader,topic,group);var observation=new Observation();var stopped=new AtomicBoolean();var ready=new CountDownLatch(1);var errors=new AtomicReference<Throwable>();
        var consumerConfig=identity(id,reader,readerPassword);consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG,group);consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");consumerConfig.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);
        var pc=identity(id,writer,password);pc.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);pc.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,ByteArraySerializer.class);pc.put(ProducerConfig.ACKS_CONFIG,"all");pc.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);pc.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);pc.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
        Thread thread=null;
        try(Admin admin=Admin.create(LocalKafka.client(id,"admin"))) {
            LocalKafka.checkCluster(admin,meta);
            try {
                admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(5,TimeUnit.SECONDS);
                if(System.getProperty("os.name").startsWith("Windows"))Files.writeString(output.resolve("retained-topics.txt"),topic+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                admin.alterUserScramCredentials(List.of(new UserScramCredentialUpsertion(writer,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),password.getBytes(StandardCharsets.UTF_8)),new UserScramCredentialUpsertion(reader,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),readerPassword.getBytes(StandardCharsets.UTF_8)))).all().get(5,TimeUnit.SECONDS);admin.createAcls(acl).all().get(5,TimeUnit.SECONDS);
                thread=new Thread(()->{boolean disconnected=false;try{
                    while(!stopped.get()&&observation.unique.size()<count){
                        boolean reopen=false;
                        try(var consumer=new KafkaConsumer<byte[],byte[]>(consumerConfig)){
                            consumer.subscribe(List.of(topic));while(!stopped.get()&&observation.unique.size()<count){
                                var batch=consumer.poll(Duration.ofMillis(100));if(!consumer.assignment().isEmpty())ready.countDown();
                                for(var record:batch)observation.received(record.value());if(!batch.isEmpty())consumer.commitSync();
                                if(scenario.equals("intermittent")&&!disconnected&&observation.unique.size()>=count/3){disconnected=true;reopen=true;break;}
                            }
                        }
                        if(reopen)Thread.sleep(pauseMs);else break;
                    }
                }catch(Throwable ex){errors.set(ex);ready.countDown();}});thread.start();
                if(!ready.await(10,TimeUnit.SECONDS)||errors.get()!=null)throw new IllegalStateException("consumer assignment failed");
                try(var producer=new KafkaProducer<byte[],byte[]>(pc)) {
                    observation.start=System.nanoTime();
                    for(int i=0;i<count;i++){
                        pace(scenario,i,count,rate,pauseMs);producer.send(new ProducerRecord<>(topic,payload(i,System.nanoTime(),size)),(r,e)->{if(e!=null)observation.failures.incrementAndGet();});
                        observation.maxBacklog.accumulateAndGet(Math.max(0,i+1-observation.unique.size()),Math::max);
                    }
                    producer.flush();long end=System.nanoTime();thread.join(20000);
                    if(thread.isAlive()||errors.get()!=null)throw new IllegalStateException("consumer did not finish");
                    return row(scenario,"KAFKA",count,observation,end,scenario.equals("intermittent")?pauseMs:0);
                }
            }finally {
                stopped.set(true);if(thread!=null&&thread.isAlive()){thread.interrupt();thread.join(5000);}
                admin.deleteAcls(acl.stream().map(AclBinding::toFilter).toList()).all().get(5,TimeUnit.SECONDS);
                admin.alterUserScramCredentials(List.of(new UserScramCredentialDeletion(writer,ScramMechanism.SCRAM_SHA_512),new UserScramCredentialDeletion(reader,ScramMechanism.SCRAM_SHA_512))).all().get(5,TimeUnit.SECONDS);
                // Kafka 3.9 native Windows online topic deletion caused a verified log-directory failure.
                // Revoke test access, record exact retained topics, and never present this as complete cleanup.
                if(!System.getProperty("os.name").startsWith("Windows")&&admin.listTopics().names().get(5,TimeUnit.SECONDS).contains(topic))admin.deleteTopics(List.of(topic)).all().get(10,TimeUnit.SECONDS);
                if(!admin.describeAcls(new AclBindingFilter(new ResourcePatternFilter(ResourceType.TOPIC,topic,PatternType.LITERAL),AccessControlEntryFilter.ANY)).values().get(5,TimeUnit.SECONDS).isEmpty())throw new IllegalStateException("cleanup not confirmed");
            }
        }
    }
    public static void main(String[] args){try {
        Properties id=LocalKafka.load(args[0]);String meta=args[1];Path output=Path.of(args[2]);int count=Integer.parseInt(args[3]),size=Integer.parseInt(args[4]),rate=Integer.parseInt(args[5]),pauseMs=Integer.parseInt(args[6]);
        if(count<30||count>2000||size<64||size>65536||rate<1||rate>1000||pauseMs<100||pauseMs>10000)throw new IllegalArgumentException();
        Files.createDirectories(output);var rows=new ArrayList<String>();rows.add("scenario,transport,offered,received_unique,errors,duplicates,payload_bytes,elapsed_s,received_per_s,p50_ms,p95_ms,drain_ms,max_unconsumed_sample,pause_ms");
        for(String scenario:List.of("peak","burst","intermittent")) {
            rows.add(rest(scenario,count,size,rate,pauseMs));Files.write(output.resolve("results.csv"),rows,StandardCharsets.UTF_8);
            rows.add(kafka(id,meta,scenario,count,size,rate,pauseMs,output));Files.write(output.resolve("results.csv"),rows,StandardCharsets.UTF_8);
            System.out.println("Completed bounded transport scenario: "+scenario);
        }
        Files.writeString(output.resolve("scope.txt"),"TRANSPORT_EXPERIMENT_ONLY\nREST uses an isolated loopback source fixture, not production Provider. Kafka uses native local Broker with temporary SCRAM principals/ACLs/topics, not platform grant workflow.\nSynthetic payloads have equal size and unique sequence IDs; offer time to receipt is measured. Kafka interval case includes committed consumer close/reopen; REST only pauses request offers. These are different recovery semantics, not a fair network outage comparison.\nNo business handling, source rows, raw bodies or credentials in output. Temporary Kafka access identities/privileges revoked. On Windows exact synthetic topic names are retained in retained-topics.txt because online deletion crashed the Broker; complete cleanup and stable deployment require a Linux environment. Small smoke loads do not prove production capacity or optimal interface selection.\n",StandardCharsets.UTF_8);
        if(rows.stream().skip(1).anyMatch(s->{String[] cells=s.split(",");return !cells[2].equals(cells[3])||!cells[4].equals("0")||!cells[5].equals("0");}))throw new IllegalStateException("incomplete experiment data");
        System.out.println("Transport experiment evidence saved; no business bodies or credentials in CSV.");
    }catch(Throwable ex){System.err.println("Interface experiment failed ("+ex.getClass().getSimpleName()+"); no success conclusion, inspect bounded evidence.");System.exit(1);}}
}
