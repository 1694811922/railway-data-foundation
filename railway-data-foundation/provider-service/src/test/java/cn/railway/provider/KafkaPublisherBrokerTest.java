package cn.railway.provider;

import kafka.testkit.KafkaClusterTestKit;
import kafka.testkit.TestKitNodes;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaPublisherBrokerTest {

    static KafkaClusterTestKit broker;
    static Admin admin;
    static final String secret=UUID.randomUUID().toString().replace("-","");
    static final String readerSecret=UUID.randomUUID().toString().replace("-","");
    static final String topic="runtime-"+UUID.randomUUID().toString().replace("-","");
    static MockEnvironment settings(String user,String password) {
        return new MockEnvironment().withProperty("PROVIDER_KAFKA_BOOTSTRAP_SERVERS",broker.bootstrapServers())
            .withProperty("PROVIDER_KAFKA_SECURITY_PROTOCOL","SASL_PLAINTEXT").withProperty("PROVIDER_KAFKA_SASL_MECHANISM","PLAIN")
            .withProperty("PROVIDER_KAFKA_USERNAME",user).withProperty("PROVIDER_KAFKA_PASSWORD",password).withProperty("PROVIDER_KAFKA_TOPIC",topic);
    }
    @BeforeAll static void start()throws Exception {
        broker=new KafkaClusterTestKit.Builder(new TestKitNodes.Builder().setCombined(true).setNumBrokerNodes(1).setNumControllerNodes(1).build())
            .setConfigProp("listeners","EXTERNAL://127.0.0.1:0,CONTROLLER://127.0.0.1:0")
            .setConfigProp("listener.security.protocol.map","EXTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
            .setConfigProp("sasl.enabled.mechanisms","PLAIN").setConfigProp("sasl.mechanism.inter.broker.protocol","PLAIN")
            .setConfigProp("listener.name.external.plain.sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\""+secret+"\" user_admin=\""+secret+"\" user_reader=\""+readerSecret+"\";")
            .setConfigProp("authorizer.class.name","org.apache.kafka.metadata.authorizer.StandardAuthorizer")
            .setConfigProp("allow.everyone.if.no.acl.found","false")
            .setConfigProp("super.users","User:admin;User:ANONYMOUS") // ANONYMOUS only on isolated loopback test controller
            .setConfigProp("offsets.topic.num.partitions",1).setConfigProp("log.index.size.max.bytes",65536).setConfigProp("log.segment.bytes",1048576).setConfigProp("offsets.topic.replication.factor",(short)1).build();
        broker.format();broker.startup();broker.waitForReadyBrokers();
        admin=Admin.create(KafkaClientSettings.create(settings("admin",secret),"PROVIDER_KAFKA_","SASL"));
        admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(5,TimeUnit.SECONDS);
    }
    @AfterAll static void stop()throws Exception {
        if(admin!=null)admin.close(Duration.ofSeconds(1));
        if(broker!=null){var directory=Path.of(broker.nodes().baseDirectory());broker.close();assertThat(directory).doesNotExist();}
    }

    ContainerEventService events() {
        var events=mock(ContainerEventService.class);var now=java.time.LocalDateTime.of(2026,10,9,0,0);
        when(events.findById(1)).thenReturn(new ContainerEventService.Event(1,"technical-test","LOAD",now,"test","test","test","OK","test","test",now,now));
        return events;
    }
    KafkaEventPublisher publisher(ContainerEventService events,MockEnvironment env) {
        return new KafkaEventPublisher(events,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),broker.bootstrapServers(),topic,env);
    }
    @Test void authenticatedPublisherObtainsRealBrokerReceiptAndCapturesExactBytes() {
        var capture=new RuntimeCapture("KAFKA_PRODUCER",null,null);
        var result=publisher(events(),settings("admin",secret).withProperty("PROVIDER_KAFKA_AUTH_TYPE","SASL")).publish(1,capture);
        assertThat(result.topic()).isEqualTo(topic);assertThat(result.offset()).isGreaterThanOrEqualTo(0);
        assertThat(capture.finish(200,0,false).recordCount()).isEqualTo(1);
        assertThat(capture.finish(200,0,false).byteCount()).isPositive();
    }
    @Test void validIdentityWithoutWriteAclAndWrongPasswordFailWithoutSuccessReceipt() {
        for(var env:List.of(settings("reader",readerSecret),settings("admin","invalid-password-12345"))) {
            assertThatThrownBy(()->publisher(events(),env.withProperty("PROVIDER_KAFKA_AUTH_TYPE","SASL")).publish(1)).isInstanceOf(KafkaEventPublisher.Unavailable.class);
        }
    }
    @Test void unsafeOrMismatchedConfigurationRejectsBeforeReadingSource() {
        var events=events();
        assertThatThrownBy(()->publisher(events,settings("admin",secret).withProperty("PROVIDER_KAFKA_AUTH_TYPE","NONE")).publish(1)).isInstanceOf(KafkaEventPublisher.Unavailable.class);
        verify(events,never()).findById(anyLong());
    }
}
