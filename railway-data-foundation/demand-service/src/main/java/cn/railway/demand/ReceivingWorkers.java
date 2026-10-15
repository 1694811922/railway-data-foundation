package cn.railway.demand;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** Single service instance; tasks survive browser logout and process restart. */
@Component @ConditionalOnProperty(name="demand.worker.enabled",havingValue="true",matchIfMissing=true)
public class ReceivingWorkers {
    private final JdbcTemplate db;private final ReceivingService tasks;private final FoundationClient foundation;private final SecretVault vault;private final KafkaConnections kafka;private final DurableInbox inbox;private final DataSource data;
    private final ExecutorService pool=Executors.newFixedThreadPool(4);private final ConcurrentMap<String,Future<?>> running=new ConcurrentHashMap<>();private volatile boolean closing=false;private Connection lease;private String leaseName;
    public ReceivingWorkers(JdbcTemplate db,ReceivingService tasks,FoundationClient foundation,SecretVault vault,KafkaConnections kafka,DurableInbox inbox,DataSource data){this.db=db;this.tasks=tasks;this.foundation=foundation;this.vault=vault;this.kafka=kafka;this.inbox=inbox;this.data=data;}
    @PostConstruct void singleInstance()throws Exception{
        lease=data.getConnection();leaseName="receiver:"+lease.getCatalog();try(var s=lease.prepareStatement("SELECT GET_LOCK(?,0)")){s.setString(1,leaseName);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("同一需求方数据库只能启动一个接收实例");}}
        db.update("UPDATE receiving_task SET runtime_state='STARTING',error_reason='' WHERE desired_state='RUNNING' AND runtime_state<>'BLOCKED'");
    }
    boolean leaseValid(){try{return !closing&&lease!=null&&lease.isValid(1);}catch(Exception e){return false;}}
    @Scheduled(fixedDelay=1000) public void schedule(){
        if(!leaseValid())return;
        running.entrySet().removeIf(e->e.getValue().isDone());
        try{
            for(var stopped:db.queryForList("SELECT task_id FROM receiving_task WHERE desired_state='STOPPED' AND runtime_state='STOPPING'")){String id=stopped.get("task_id").toString();if(!running.containsKey(id))db.update("UPDATE receiving_task SET runtime_state='STOPPED' WHERE task_id=? AND desired_state='STOPPED'",id);}
            for(var row:db.queryForList("SELECT task_id FROM receiving_task WHERE desired_state='RUNNING' AND runtime_state<>'BLOCKED' ORDER BY created_at")){
                String id=row.get("task_id").toString();if(running.size()>=4)break;if(!running.containsKey(id))running.put(id,pool.submit(()->run(id)));
            }
        }catch(org.springframework.dao.DataAccessException ignored){/* database unavailable: workers do not acknowledge messages */}
    }
    boolean desired(String id){if(!leaseValid())return false;return "RUNNING".equals(db.queryForObject("SELECT desired_state FROM receiving_task WHERE task_id=?",String.class,id));}
    void status(String id,String state,String error){try{db.update("UPDATE receiving_task SET runtime_state=?,error_reason=?,updated_at=UTC_TIMESTAMP(3) WHERE task_id=? AND desired_state='RUNNING'",state,error,id);}catch(org.springframework.dao.DataAccessException ignored){}}
    void run(String id){
        int failures=0;
        while(!closing){
            try{
                if(!desired(id))return;
                var row=db.queryForMap("SELECT * FROM receiving_task WHERE task_id=?",id);var ctx=tasks.read(row.get("context_json").toString());var secret=tasks.read(vault.open(id,row.get("encrypted_secrets").toString()));
                String grant=row.get("grant_id").toString(),token=secret.path("token").asText(),password=secret.path("password").asText();
                ReceivingService.sameContext(ctx,foundation.worker(grant,token));String identity=kafka.identity(ctx,password);String topic=ctx.path("grant").path("topic").asText();
                try(var consumer=new KafkaConsumer<byte[],byte[]>(kafka.properties(ctx,password))){
                    consumer.subscribe(List.of(topic),new ConsumerRebalanceListener(){public void onPartitionsRevoked(Collection<TopicPartition> p){}public void onPartitionsAssigned(Collection<TopicPartition> p){
                        var beginnings=consumer.beginningOffsets(p);var ends=consumer.endOffsets(p);
                        for(var partition:p){long initial="LATEST".equals(row.get("start_position"))?ends.get(partition):beginnings.get(partition);
                            consumer.seek(partition,inbox.checkpoint(id,identity,partition.partition(),initial,beginnings.get(partition),ends.get(partition)));}
                    }});
                    long lastCheck=0;
                    while(desired(id)){
                        Instant start=Instant.now().truncatedTo(ChronoUnit.MILLIS);var records=consumer.poll(Duration.ofMillis(500));Instant end=Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        if(!records.isEmpty()||System.nanoTime()-lastCheck>Duration.ofSeconds(3).toNanos()){
                            ReceivingService.sameContext(ctx,foundation.worker(grant,token));if(!identity.equals(kafka.identity(ctx,password)))throw new ReceiverFailure(400,"主题已重建，任务已阻塞");lastCheck=System.nanoTime();
                        }
                        if(!desired(id))break;
                        if(!consumer.assignment().isEmpty())status(id,"RUNNING","");
                        if(!records.isEmpty()){
                            var batch=new ArrayList<ConsumerRecord<byte[],byte[]>>();records.forEach(batch::add);
                            deliverAndCommit(inbox,consumer,id,identity,topic,batch,start,end);failures=0;
                        }
                    }
                }return;
            }catch(ReceiverFailure e){if(e.status<500){status(id,"BLOCKED",e.getMessage());return;}status(id,"RETRYING","底座授权核验暂不可用，已暂停读取");}
            catch(AuthenticationException|AuthorizationException e){status(id,"BLOCKED","Kafka认证或授权失败，请检查凭据和授权");return;}
            catch(org.apache.kafka.clients.consumer.OffsetOutOfRangeException|NoOffsetForPartitionException e){status(id,"BLOCKED","消费位置已失效，未跳过缺失历史");return;}
            catch(Exception e){status(id,"RETRYING","连接、落库或位置提交未确认，等待恢复");}
            if(++failures>=8){status(id,"BLOCKED","连续恢复失败，请处理错误后手动恢复");return;}
            try{Thread.sleep(Math.min(30000,1000L<<(failures-1)));}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
    }
    static void deliverAndCommit(DurableInbox inbox,Consumer<byte[],byte[]> consumer,String id,String identity,String topic,List<ConsumerRecord<byte[],byte[]>> batch,Instant start,Instant end){
        var positions=inbox.save(id,identity,batch,start,end);var offsets=new HashMap<TopicPartition,OffsetAndMetadata>();positions.forEach((p,o)->offsets.put(new TopicPartition(topic,p),new OffsetAndMetadata(o)));
        consumer.commitSync(offsets);
    }
    @Scheduled(fixedDelay=3000) public void report(){
        if(!leaseValid())return;
        try{for(var row:db.queryForList("SELECT o.report_id,o.report_json,o.attempts,t.task_id,t.grant_id,t.encrypted_secrets FROM receiving_outbox o JOIN receiving_task t ON t.task_id=o.task_id WHERE o.report_state='PENDING' AND o.next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY o.created_at LIMIT 20")){
            String report=row.get("report_id").toString();
            try{
                var secret=tasks.read(vault.open(row.get("task_id").toString(),row.get("encrypted_secrets").toString()));var reply=foundation.report(row.get("grant_id").toString(),secret.path("token").asText(),row.get("report_json").toString());var sent=tasks.read(row.get("report_json").toString());
                long bytes=0;for(var p:sent.path("positions"))bytes+=p.path("byte_count").asLong();
                if(!report.equals(reply.path("report_id").asText())||reply.path("records").asInt()!=sent.path("positions").size()||reply.path("bytes").asLong()!=bytes)throw new ReceiverFailure(503,"摘要回执不一致");
                db.update("UPDATE receiving_outbox SET report_state='SENT',attempts=attempts+1 WHERE report_id=?",report);
            }catch(ReceiverFailure e){if(e.status<500)db.update("UPDATE receiving_outbox SET report_state='REJECTED',attempts=attempts+1 WHERE report_id=?",report);else db.update("UPDATE receiving_outbox SET attempts=attempts+1,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE report_id=?",report);}
        }}catch(org.springframework.dao.DataAccessException ignored){}
    }
    @PreDestroy public void close(){closing=true;pool.shutdownNow();try{pool.awaitTermination(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(lease!=null)try{try(var s=lease.prepareStatement("SELECT RELEASE_LOCK(?)")){s.setString(1,leaseName);s.execute();}lease.close();}catch(Exception ignored){}}
}
