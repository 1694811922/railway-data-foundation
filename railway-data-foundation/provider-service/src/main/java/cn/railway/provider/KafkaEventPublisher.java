package cn.railway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class KafkaEventPublisher {
    public record Receipt(String topic, int partition, long offset) {}
    public static class Unavailable extends RuntimeException {}

    private final ContainerEventService events;
    private final ObjectMapper json;
    private final String brokers;
    private final String topic;
    private final Environment environment;

    public KafkaEventPublisher(ContainerEventService events, ObjectMapper json,
            @Value("${PROVIDER_KAFKA_BOOTSTRAP_SERVERS:}") String brokers,
            @Value("${PROVIDER_KAFKA_TOPIC:}") String topic,Environment environment) {
        this.events=events;
        this.json=json;
        this.brokers=brokers;
        this.topic=topic;
        this.environment=environment;
    }

    /** Provider reads Source and publishes directly. Foundation never handles the event payload. */
    public Receipt publish(long id) {
        return publish(id,null);
    }
    public Receipt publish(long id,RuntimeCapture capture) {
        if (brokers.isBlank() || !topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}"))
            throw new Unavailable();
        final Properties config;
        try {config=KafkaClientSettings.create(environment,"PROVIDER_KAFKA_",environment.getProperty("PROVIDER_KAFKA_AUTH_TYPE","NONE"));}
        catch(IllegalArgumentException e){throw new Unavailable();}
        var event=events.findById(id);
        if (event==null) throw new IllegalArgumentException("事件不存在");
        final String payload;
        try { payload=json.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new Unavailable(); }
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG,"all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,3000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5000);
        final KafkaProducer<String,String> producer;
        try {producer=new KafkaProducer<>(config);} catch(org.apache.kafka.common.KafkaException ex){throw new Unavailable();}
        try {
            var result=producer.send(new ProducerRecord<>(topic,event.containerNo(),payload)).get(6,TimeUnit.SECONDS);
            var receipt=new Receipt(result.topic(),result.partition(),result.offset());
            if(capture!=null)capture.kafkaSuccess(receipt,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return receipt;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (ExecutionException | TimeoutException | IllegalArgumentException | org.apache.kafka.common.KafkaException ex) {
            throw new Unavailable();
        } finally {producer.close(java.time.Duration.ofSeconds(1));}
    }
}
