package cn.railway.example.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;

/** External caller process, never a consumer in Foundation. Prints metadata only. */
public final class KafkaSubscriber {
    public static void main(String[] args) {
        try {
            var config=KafkaCallerConfig.create(env("KAFKA_DEMAND_BROKERS"),env("KAFKA_DEMAND_PROTOCOL"),env("KAFKA_DEMAND_MECHANISM"),env("KAFKA_DEMAND_USERNAME"),env("KAFKA_DEMAND_PASSWORD"));
            String topic=env("KAFKA_DEMAND_TOPIC"),group=env("KAFKA_DEMAND_GROUP");
            if(!ExactSubscriptionAcl.name(topic)||!ExactSubscriptionAcl.name(group))throw new IllegalArgumentException();
            int maximum=number("KAFKA_DEMAND_MAX_MESSAGES",10,1,1000),seconds=number("KAFKA_DEMAND_SECONDS",15,1,60);
            config.put(ConsumerConfig.GROUP_ID_CONFIG,group);
            config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
            String reset=env("KAFKA_DEMAND_OFFSET_RESET");if(reset==null)reset="latest";if(!List.of("latest","earliest").contains(reset))throw new IllegalArgumentException();
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,reset);
            config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
            config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,Math.min(maximum,100));
            String reportBase=env("KAFKA_DEMAND_FOUNDATION_URL"),reportGrant=env("KAFKA_DEMAND_GRANT_ID"),reportToken=env("KAFKA_DEMAND_REPORT_TOKEN");
            ConsumptionReporter reporter=reportBase==null&&reportGrant==null&&reportToken==null?null:new ConsumptionReporter(reportBase,reportGrant,reportToken);
            int unconfirmed=0;
            int seen=0;long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
            try(var consumer=new KafkaConsumer<byte[],byte[]>(config)){
                consumer.subscribe(List.of(topic));
                boolean ready=false;
                while(seen<maximum&&System.nanoTime()<deadline){
                    Instant started=Instant.now().truncatedTo(ChronoUnit.MILLIS);
                    var page=consumer.poll(Duration.ofMillis(250));
                    Instant completed=Instant.now().truncatedTo(ChronoUnit.MILLIS);
                    var positions=new ArrayList<ConsumptionReporter.Position>();
                    if(!ready&&!consumer.assignment().isEmpty()){
                        for(var partition:consumer.assignment())consumer.position(partition);
                        System.out.printf("订阅已建立：主题 %s，消费组 %s%n",topic,group);ready=true;
                    }
                    for(var message:page){
                        // No message keys or values in output. Integrators may process in their own system.
                        int bytes=message.value()==null?0:message.value().length;
                        System.out.printf("已观察消息：主题 %s，分区 %d，位点 %d，载荷字节 %d%n",message.topic(),message.partition(),message.offset(),bytes);
                        positions.add(new ConsumptionReporter.Position(message.partition(),message.offset(),bytes));
                        if(++seen>=maximum)break;
                    }
                    if(reporter!=null&&!positions.isEmpty()){
                        var batch=new ConsumptionReporter.Batch(UUID.randomUUID().toString(),started.toString(),completed.toString(),positions);
                        if(reporter.send(batch))System.out.println("消费观察摘要已确认上报；未上传业务消息。");
                        else{unconfirmed++;System.err.println("消费摘要上报未确认；不影响Broker访问，不能将未上报计为零。");}
                    }
                }
                if(!ready)throw new IllegalStateException("订阅未建立");
            }
            System.out.printf("本次已观察 %d 条消息；未提交消费位点，不代表端到端业务处理完成。%n",seen);
            if(reporter==null)System.out.println("未启用消费摘要上报；底座消费数量仍未知。");
            if(unconfirmed>0){System.err.println("存在未确认的摘要，本示例没有持久补报，进程结束后不能恢复。");System.exit(2);}
        }catch(AuthenticationException ex){error("Broker身份认证失败，请使用独立Kafka凭据");}
        catch(AuthorizationException ex){error("Broker拒绝主题或消费组访问，请由管理员检查实际授权");}
        catch(IllegalArgumentException ex){error("订阅参数不合法，检查Broker、主题、消费组、SASL认证与凭据");}
        catch(Exception ex){error("订阅未完成，检查Broker连接；不自动降级或重试业务处理");}
    }
    private static String env(String key){return System.getenv(key);}
    private static int number(String key,int fallback,int min,int max){String value=env(key);int result=value==null?fallback:Integer.parseInt(value);if(result<min||result>max)throw new IllegalArgumentException();return result;}
    private static void error(String message){System.err.println(message);System.exit(1);}
}
