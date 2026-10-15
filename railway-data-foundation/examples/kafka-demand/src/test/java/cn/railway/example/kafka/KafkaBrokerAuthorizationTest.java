package cn.railway.example.kafka;

import kafka.testkit.KafkaClusterTestKit;
import kafka.testkit.TestKitNodes;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.apache.kafka.common.errors.*;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/** Real ephemeral KRaft Broker with SASL identity and StandardAuthorizer, not an HTTP mock. */
class KafkaBrokerAuthorizationTest {
    static KafkaClusterTestKit broker;
    static Admin admin;
    static String secret=UUID.randomUUID().toString().replace("-","");
    static String readerSecret=UUID.randomUUID().toString().replace("-","");
    static Properties config(String user,String password){return KafkaCallerConfig.create(broker.bootstrapServers(),"SASL_PLAINTEXT","PLAIN",user,password);}
    @BeforeAll static void start()throws Exception {
        broker=new KafkaClusterTestKit.Builder(new TestKitNodes.Builder().setCombined(true).setNumBrokerNodes(1).setNumControllerNodes(1).build())
                .setConfigProp("listeners","EXTERNAL://127.0.0.1:0,CONTROLLER://127.0.0.1:0")
                .setConfigProp("listener.security.protocol.map","EXTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                .setConfigProp("sasl.enabled.mechanisms","PLAIN")
                .setConfigProp("sasl.mechanism.inter.broker.protocol","PLAIN")
                .setConfigProp("listener.name.external.plain.sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\""+secret+"\" user_admin=\""+secret+"\" user_reader=\""+readerSecret+"\";")
                .setConfigProp("authorizer.class.name","org.apache.kafka.metadata.authorizer.StandardAuthorizer")
                .setConfigProp("allow.everyone.if.no.acl.found","false")
                .setConfigProp("super.users","User:admin;User:ANONYMOUS") // only loopback controller listener uses ANONYMOUS
                .setConfigProp("offsets.topic.num.partitions",1).setConfigProp("log.index.size.max.bytes",65536).setConfigProp("log.segment.bytes",1048576).setConfigProp("offsets.topic.replication.factor",(short)1)
                .setConfigProp("group.initial.rebalance.delay.ms",0).build();
        broker.format();broker.startup();broker.waitForReadyBrokers();
        admin=Admin.create(config("admin",secret));
    }
    @AfterAll static void stop()throws Exception {
        if(admin!=null)admin.close(Duration.ofSeconds(3));
        if(broker!=null){var temporary=Path.of(broker.nodes().baseDirectory());broker.close();assertThat(temporary).doesNotExist();}
        secret="";readerSecret="";
    }
    String topic,group;
    @BeforeEach void prepare()throws Exception {
        topic="acl-demo-"+UUID.randomUUID().toString().replace("-","");group="group-"+UUID.randomUUID().toString().replace("-","");
        admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(5,TimeUnit.SECONDS);
        produce(topic);
    }
    @AfterEach void clean()throws Exception {
        ExactSubscriptionAcl.revoke(admin,"reader",topic,group);
        // Windows may keep segment files mapped while the broker is running.
        // The isolated TestKit removes all its topics and files after broker shutdown.
    }
    static void produce(String topic)throws Exception {
        var p=config("admin",secret);p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,3000);
        try(var producer=new KafkaProducer<String,String>(p)){producer.send(new ProducerRecord<>(topic,"example-key","{\"example\":true}")).get(5,TimeUnit.SECONDS);}
    }
    KafkaConsumer<String,String> consumer(String group,String password){
        var p=config("reader",password);p.put(ConsumerConfig.GROUP_ID_CONFIG,group);p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,50);
        return new KafkaConsumer<>(p);
    }
    void receive(KafkaConsumer<String,String> consumer,String topic){
        consumer.subscribe(List.of(topic));long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<until)for(var record:consumer.poll(Duration.ofMillis(100))){assertThat(record.topic()).isEqualTo(topic);return;}
        fail("Broker未在期限内返回已授权的样例消息");
    }
    void denied(KafkaConsumer<String,String> consumer,String topic,Class<? extends Exception> type){
        consumer.subscribe(List.of(topic));long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<until){try{assertThat(consumer.poll(Duration.ofMillis(100))).isEmpty();}catch(Exception ex){assertThat(ex).isInstanceOf(type);return;}}
        fail("Broker未在期限内明确拒绝未授权请求");
    }
    @Test void noAclDeniesSubscriptionEvenWithValidIdentity(){try(var c=consumer(group,readerSecret)){denied(c,topic,TopicAuthorizationException.class);}}
    @Test void exactTopicAndGroupGrantAllowsRealMessage()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        try(var c=consumer(group,readerSecret)){receive(c,topic);}
    }
    @Test void wrongBrokerPasswordCannotAuthenticate()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        try(var c=consumer(group,"invalid-"+UUID.randomUUID())){denied(c,topic,AuthenticationException.class);}
    }
    @Test void anotherConsumerGroupIsDenied()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        try(var c=consumer(group+"-other",readerSecret)){denied(c,topic,GroupAuthorizationException.class);}
    }
    @Test void anotherTopicIsDenied()throws Exception {
        String other=topic+"-other";admin.createTopics(List.of(new NewTopic(other,1,(short)1))).all().get(5,TimeUnit.SECONDS);
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);try(var c=consumer(group,readerSecret)){denied(c,other,TopicAuthorizationException.class);}
    }
    @Test void readPermissionDoesNotAllowPublishing()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        var p=config("reader",readerSecret);p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,3000);p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,false);
        try(var producer=new KafkaProducer<String,String>(p)){
            assertThatThrownBy(()->producer.send(new ProducerRecord<>(topic,"key","forbidden")).get(5,TimeUnit.SECONDS)).hasRootCauseInstanceOf(TopicAuthorizationException.class);
        }
    }
    @Test void removingTopicReadAclRevokesAnEstablishedConnection()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        try(var c=consumer(group,readerSecret)){
            receive(c,topic);
            ExactSubscriptionAcl.revoke(admin,"reader",topic,group);
            // Do not claim already buffered or delivered records can be withdrawn.
            long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();boolean refused=false;
            while(System.nanoTime()<until){try{c.poll(Duration.ofMillis(100));}catch(AuthorizationException ex){refused=true;break;}}
            assertThat(refused).as("已建立连接的后续Broker请求必须拒绝").isTrue();
        }
    }
    @Test void revokeIsExactAndRepeatablePreservesOtherGrant()throws Exception {
        String otherGroup=group+"-other";
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);ExactSubscriptionAcl.grant(admin,"reader",topic,otherGroup);
        // Topic ACL is shared by these grants; this helper is deliberately not a multi-grant reconciler.
        ExactSubscriptionAcl.revoke(admin,"reader",topic,group);ExactSubscriptionAcl.revoke(admin,"reader",topic,group);
        var remaining=admin.describeAcls(new AclBindingFilter(new ResourcePatternFilter(ResourceType.GROUP,otherGroup,PatternType.LITERAL),new AccessControlEntryFilter("User:reader","*",AclOperation.READ,AclPermissionType.ALLOW))).values().get(5,TimeUnit.SECONDS);
        assertThat(remaining).hasSize(1);
        ExactSubscriptionAcl.revoke(admin,"reader",topic,otherGroup);
    }
    @Test void directPartitionFetchCannotBypassTopicReadAcl(){
        try(var c=consumer(group,readerSecret)){
            c.assign(List.of(new TopicPartition(topic,0)));c.seek(new TopicPartition(topic,0),0);
            assertThatThrownBy(()->{long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end)c.poll(Duration.ofMillis(100));}).isInstanceOf(TopicAuthorizationException.class);
        }
    }
    @Test void restTokenAndUnsafeClientConfigsAreRejected(){
        for(var protocol:List.of("PLAINTEXT","SSL"))assertThatThrownBy(()->KafkaCallerConfig.create("127.0.0.1:9092",protocol,"PLAIN","reader",readerSecret)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->KafkaCallerConfig.create("remote.example:9092","SASL_PLAINTEXT","PLAIN","reader",readerSecret)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->KafkaCallerConfig.create("127.0.0.1:9092","SASL_PLAINTEXT","PLAIN","reader","rdf_"+"a".repeat(43))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExactSubscriptionAcl.bindings("reader","*",group)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExactSubscriptionAcl.bindings("User:*",topic,group)).isInstanceOf(IllegalArgumentException.class);
    }
    Process subscriber()throws Exception {
        return subscriber(broker.bootstrapServers(),15);
    }
    Process subscriber(String brokers,int seconds)throws Exception {
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms16m","-Xmx128m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=2","-Dfile.encoding=UTF-8","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),KafkaSubscriber.class.getName());
        var env=command.environment();env.clear();if(System.getenv("SystemRoot")!=null)env.put("SystemRoot",System.getenv("SystemRoot"));
        env.put("KAFKA_DEMAND_BROKERS",brokers);env.put("KAFKA_DEMAND_PROTOCOL","SASL_PLAINTEXT");env.put("KAFKA_DEMAND_MECHANISM","PLAIN");env.put("KAFKA_DEMAND_USERNAME","reader");env.put("KAFKA_DEMAND_PASSWORD",readerSecret);env.put("KAFKA_DEMAND_TOPIC",topic);env.put("KAFKA_DEMAND_GROUP",group);env.put("KAFKA_DEMAND_MAX_MESSAGES","1");env.put("KAFKA_DEMAND_SECONDS",Integer.toString(seconds));
        return command.start();
    }
    @Test void executableSubscriberReceivesNewMessageAndPrintsMetadataOnly()throws Exception {
        ExactSubscriptionAcl.grant(admin,"reader",topic,group);
        var process=subscriber();var thread=Executors.newSingleThreadExecutor();
        try(var out=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
            String ready=thread.submit(out::readLine).get(10,TimeUnit.SECONDS);assertThat(ready).contains("订阅已建立");
            produce(topic);
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
            String stdout=ready+out.lines().collect(java.util.stream.Collectors.joining("\n"));
            assertThat(stdout).contains("已观察消息", "本次已观察 1 条").doesNotContain("example-key", "example\":true",readerSecret);
            assertThat(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8)).doesNotContain(readerSecret);
        }finally{process.destroyForcibly();thread.shutdownNow();}
    }
    @Test void executableSubscriberExitsWithFailureForActualBrokerDenial()throws Exception {
        var process=subscriber();
        try{
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isEqualTo(1);
            assertThat(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8)).contains("Broker拒绝").doesNotContain(readerSecret);
        }finally{process.destroyForcibly();}
    }
    @Test void unreachableBrokerDoesNotBecomeSuccessfulZeroMessages()throws Exception {
        var process=subscriber("127.0.0.1:1",2);
        try{
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isEqualTo(1);
            assertThat(new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).doesNotContain("本次已观察 0 条");
            assertThat(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8)).contains("订阅未完成").doesNotContain(readerSecret);
        }finally{process.destroyForcibly();}
    }
}
