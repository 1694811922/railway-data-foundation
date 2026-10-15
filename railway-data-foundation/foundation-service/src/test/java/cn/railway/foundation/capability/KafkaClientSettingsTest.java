package cn.railway.foundation.capability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class KafkaClientSettingsTest {
    MockEnvironment env(String brokers,String protocol) {
        return new MockEnvironment().withProperty("FOUNDATION_KAFKA_BOOTSTRAP_SERVERS",brokers)
            .withProperty("FOUNDATION_KAFKA_SECURITY_PROTOCOL",protocol)
            .withProperty("FOUNDATION_KAFKA_SASL_MECHANISM","SCRAM-SHA-512")
            .withProperty("FOUNDATION_KAFKA_USERNAME","publisher")
            .withProperty("FOUNDATION_KAFKA_PASSWORD","safe-password-0123456789");
    }
    @Test void plaintextIsLoopbackOnlyAndMustMatchRegisteredAuthentication() {
        assertThat(KafkaClientSettings.create(env("127.0.0.1:9092","PLAINTEXT"),"FOUNDATION_KAFKA_","NONE").getProperty("security.protocol")).isEqualTo("PLAINTEXT");
        for(String brokers:new String[]{"external:9092","127.0.0.1:9092,external:9092","user:secret@127.0.0.1:9092","127.0.0.1:9092/private","127.0.0.1:0","127.0.0.1:9092,"})
            assertThatThrownBy(()->KafkaClientSettings.create(env(brokers,"PLAINTEXT"),"FOUNDATION_KAFKA_","NONE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->KafkaClientSettings.create(env("localhost:9092","PLAINTEXT"),"FOUNDATION_KAFKA_","SASL")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void saslRequiresIndependentCredentialsAndRemoteTls() {
        var settings=KafkaClientSettings.create(env("broker.example:9093","SASL_SSL"),"FOUNDATION_KAFKA_","SASL");
        assertThat(settings.getProperty("sasl.mechanism")).isEqualTo("SCRAM-SHA-512");
        assertThat(settings.getProperty("sasl.jaas.config")).contains("ScramLoginModule");
        assertThatThrownBy(()->KafkaClientSettings.create(env("broker.example:9092","SASL_PLAINTEXT"),"FOUNDATION_KAFKA_","SASL")).isInstanceOf(IllegalArgumentException.class);
        var bad=env("localhost:9092","SASL_PLAINTEXT").withProperty("FOUNDATION_KAFKA_PASSWORD","rdf_"+"x".repeat(43));
        assertThatThrownBy(()->KafkaClientSettings.create(bad,"FOUNDATION_KAFKA_","SASL")).hasMessage("Kafka连接或认证配置不合法，请检查后端运维配置");
    }
    @Test void credentialsAreEscapedAndControlCharactersRejected() {
        var escaped=env("localhost:9092","SASL_PLAINTEXT").withProperty("FOUNDATION_KAFKA_PASSWORD","password-with-quote-\\\"-tail");
        assertThat(KafkaClientSettings.create(escaped,"FOUNDATION_KAFKA_","SASL").getProperty("sasl.jaas.config")).contains("\\\\\\\"");
        assertThatThrownBy(()->KafkaClientSettings.create(escaped.withProperty("FOUNDATION_KAFKA_PASSWORD","password-secret\n-tail"),"FOUNDATION_KAFKA_","SASL")).isInstanceOf(IllegalArgumentException.class);
    }
}
