package cn.railway.demand;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
@Component
public class KafkaConnections {
    private final Environment env;
    public KafkaConnections(Environment env){this.env=env;}
    public Properties properties(JsonNode ctx,String password){
        String brokers=ctx.path("bootstrap_servers").asText(),protocol=ctx.path("security_protocol").asText(),user=ctx.path("grant").path("broker_username").asText();
        if(!user.matches("rdfk_[a-f0-9]{32}")||password==null||!password.matches("ks_[A-Za-z0-9_-]{43}")||!Set.of("SASL_SSL","SASL_PLAINTEXT").contains(protocol)||!"SCRAM-SHA-512".equals(ctx.path("sasl_mechanism").asText()))throw new ReceiverFailure(400,"Kafka凭据或认证配置无效");
        boolean local=Arrays.stream(brokers.split(",")).allMatch(b->b.trim().matches("(127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}"));
        if(brokers.isBlank()||(!local&&!protocol.equals("SASL_SSL")))throw new ReceiverFailure(400,"远程Kafka必须使用加密连接");
        var p=new Properties();p.put("bootstrap.servers",brokers);p.put("security.protocol",protocol);p.put("sasl.mechanism","SCRAM-SHA-512");
        p.put("sasl.jaas.config","org.apache.kafka.common.security.scram.ScramLoginModule required username=\""+user+"\" password=\""+password+"\";");
        p.put("ssl.endpoint.identification.algorithm","https");
        for(String name:List.of("LOCATION","PASSWORD")){var v=env.getProperty("DEMAND_KAFKA_SSL_TRUSTSTORE_"+name);if(v!=null&&!v.isBlank())p.put("ssl.truststore."+name.toLowerCase(Locale.ROOT),v);}
        p.put("default.api.timeout.ms",5000);p.put("request.timeout.ms",3000);
        p.put("group.id",ctx.path("grant").path("consumer_group").asText());p.put("enable.auto.commit",false);p.put("auto.offset.reset","none");p.put("allow.auto.create.topics",false);
        p.put("key.deserializer",ByteArrayDeserializer.class);p.put("value.deserializer",ByteArrayDeserializer.class);p.put("max.poll.records",100);p.put("max.poll.interval.ms",120000);
        p.put("session.timeout.ms",env.getProperty("DEMAND_KAFKA_SESSION_TIMEOUT_MS",Integer.class,10000));p.put("heartbeat.interval.ms",3000);
        return p;
    }
    public String identity(JsonNode ctx,String password){
        try(var admin=Admin.create(properties(ctx,password))){
            String cluster=admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS);if(!cluster.equals(ctx.path("cluster_id").asText()))throw new ReceiverFailure(400,"Kafka集群身份不一致");
            var topic=admin.describeTopics(List.of(ctx.path("grant").path("topic").asText())).allTopicNames().get(5,TimeUnit.SECONDS).values().iterator().next();
            if(topic.isInternal()||topic.topicId().equals(org.apache.kafka.common.Uuid.ZERO_UUID)||topic.partitions().isEmpty())throw new ReceiverFailure(400,"主题身份无法确认");return cluster+":"+topic.topicId();
        }catch(ReceiverFailure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ReceiverFailure(400,"Kafka认证或主题核验失败，请检查专属凭据");}
    }
    public void verify(JsonNode ctx,String password){
        identity(ctx,password);
        try(var c=new KafkaConsumer<byte[],byte[]>(properties(ctx,password))){
            var parts=c.partitionsFor(ctx.path("grant").path("topic").asText());
            c.committed(new HashSet<>(parts.stream().map(p->new org.apache.kafka.common.TopicPartition(p.topic(),p.partition())).toList()),Duration.ofSeconds(5));
        }catch(Exception e){throw new ReceiverFailure(400,"Kafka消费组权限或凭据核验失败");}
    }
    public record Position(long earliest,long latest,Long committed) {}
    public record Positions(String identity,String topic,Map<Integer,Position> partitions) {}
    /** Metadata queries only. Does not create a consumer, join a group, poll or commit. */
    public Positions positions(JsonNode ctx,String password){
        Admin admin=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try{
            admin=Admin.create(properties(ctx,password));
            String cluster=await(admin.describeCluster().clusterId(),deadline);
            if(!cluster.equals(ctx.path("cluster_id").asText()))throw new ReceiverFailure(400,"Kafka集群身份不一致");
            String name=ctx.path("grant").path("topic").asText();
            var description=await(admin.describeTopics(List.of(name)).allTopicNames(),deadline).get(name);
            if(description==null||description.isInternal()||description.topicId().equals(org.apache.kafka.common.Uuid.ZERO_UUID)||description.partitions().isEmpty())throw new ReceiverFailure(400,"主题身份无法确认");
            Map<org.apache.kafka.common.TopicPartition,OffsetSpec> beginnings=new HashMap<>(),ends=new HashMap<>();
            for(var p:description.partitions()){var tp=new org.apache.kafka.common.TopicPartition(name,p.partition());beginnings.put(tp,OffsetSpec.earliest());ends.put(tp,OffsetSpec.latest());}
            var beginningFuture=admin.listOffsets(beginnings).all();var endFuture=admin.listOffsets(ends).all();
            var committedFuture=admin.listConsumerGroupOffsets(ctx.path("grant").path("consumer_group").asText()).partitionsToOffsetAndMetadata();
            var first=await(beginningFuture,deadline);var last=await(endFuture,deadline);var committed=await(committedFuture,deadline);
            var result=new TreeMap<Integer,Position>();for(var tp:ends.keySet()){
                if(first.get(tp)==null||last.get(tp)==null||first.get(tp).offset()<0||last.get(tp).offset()<first.get(tp).offset())throw new ReceiverFailure(503,"主题位置响应不完整，请重试");
                result.put(tp.partition(),new Position(first.get(tp).offset(),last.get(tp).offset(),committed.get(tp)==null?null:committed.get(tp).offset()));
            }
            // Reconfirm identity after the offset queries, so recreation is not presented as ordinary lag.
            var after=await(admin.describeTopics(List.of(name)).allTopicNames(),deadline).get(name);
            if(after==null||!after.topicId().equals(description.topicId())||after.partitions().size()!=description.partitions().size())throw new ReceiverFailure(400,"主题在查询期间发生变化，请重试");
            return new Positions(cluster+":"+description.topicId(),name,Map.copyOf(result));
        }catch(ReceiverFailure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ReceiverFailure(503,"Kafka位置查询暂不可用，请检查授权、凭据或连接后重试");}
        finally{if(admin!=null)admin.close(Duration.ofMillis(500));}
    }
    private static <T>T await(org.apache.kafka.common.KafkaFuture<T> future,long deadline)throws Exception{
        long left=deadline-System.nanoTime();if(left<=0)throw new java.util.concurrent.TimeoutException();return future.get(left,TimeUnit.NANOSECONDS);
    }
}
