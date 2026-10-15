package cn.railway.foundation.capability;

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

class KafkaVerifierBrokerTest {

    static KafkaClusterTestKit broker;
    static Admin admin;
    static final String secret=UUID.randomUUID().toString().replace("-","");
    static final String readerSecret=UUID.randomUUID().toString().replace("-","");
    static final String topic="runtime-"+UUID.randomUUID().toString().replace("-","");
    static MockEnvironment settings(String user,String password) {
        return new MockEnvironment().withProperty("FOUNDATION_KAFKA_BOOTSTRAP_SERVERS",broker.bootstrapServers())
            .withProperty("FOUNDATION_KAFKA_SECURITY_PROTOCOL","SASL_PLAINTEXT").withProperty("FOUNDATION_KAFKA_SASL_MECHANISM","PLAIN")
            .withProperty("FOUNDATION_KAFKA_USERNAME",user).withProperty("FOUNDATION_KAFKA_PASSWORD",password).withProperty("FOUNDATION_KAFKA_TOPIC",topic);
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
        admin=Admin.create(KafkaClientSettings.create(settings("admin",secret),"FOUNDATION_KAFKA_","SASL"));
        admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(5,TimeUnit.SECONDS);
    }
    @AfterAll static void stop()throws Exception {
        if(admin!=null)admin.close(Duration.ofSeconds(1));
        if(broker!=null){var directory=Path.of(broker.nodes().baseDirectory());broker.close();assertThat(directory).doesNotExist();}
    }

    final ProviderSourceBinding binding=mock(ProviderSourceBinding.class);
    cn.railway.foundation.capability.CapabilityService.Capability capability(String name,String auth) {
        return new CapabilityService.Capability("CAP_"+"1".repeat(32),"SASL测试","RES_"+"2".repeat(32),"测试资源","KAFKA_TOPIC","","",name,"","KAFKA",auth,"","","","DRAFT","UNKNOWN",1,null,null);
    }
    @Test void authenticatedMetadataProbeUsesRealBrokerButNeverConsumesMessages() {
        assertThat(new KafkaCapabilityVerifier(settings("admin",secret),binding).probe(capability(topic,"SASL"))).isTrue();
        verify(binding).requireSupported("RES_"+"2".repeat(32));
    }
    @Test void wrongPasswordAndMissingDescribePermissionAreNotSuccessfulValidation() {
        assertThat(new KafkaCapabilityVerifier(settings("admin","invalid-password-12345"),binding).probe(capability(topic,"SASL"))).isFalse();
        assertThat(new KafkaCapabilityVerifier(settings("reader",readerSecret),binding).probe(capability(topic,"SASL"))).isFalse();
    }
    @Test void wrongTopicAndSourceAreRejectedBeforeBrokerProbe() {
        assertThatThrownBy(()->new KafkaCapabilityVerifier(settings("admin",secret),binding).probe(capability(topic+"-foreign","SASL"))).isInstanceOf(cn.railway.foundation.api.BusinessException.class);
        doThrow(new cn.railway.foundation.api.BusinessException(cn.railway.foundation.api.ErrorCode.BUSINESS_ERROR,"来源已停用")).when(binding).requireSupported(anyString());
        assertThatThrownBy(()->new KafkaCapabilityVerifier(settings("admin",secret),binding).probe(capability(topic,"SASL"))).hasMessage("来源已停用");
    }
    @Test void missingTopicOrWrongAuthenticationCannotEnableTheCapability() {
        var missing=topic+"-missing";
        assertThat(new KafkaCapabilityVerifier(settings("admin",secret).withProperty("FOUNDATION_KAFKA_TOPIC",missing),binding).probe(capability(missing,"SASL"))).isFalse();
        assertThatThrownBy(()->new KafkaCapabilityVerifier(settings("admin",secret),binding).probe(capability(topic,"NONE"))).isInstanceOf(cn.railway.foundation.api.BusinessException.class);
    }
}
