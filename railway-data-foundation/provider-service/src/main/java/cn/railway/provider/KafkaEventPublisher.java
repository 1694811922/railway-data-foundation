package cn.railway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public KafkaEventPublisher(ContainerEventService events, ObjectMapper json,
            @Value("${PROVIDER_KAFKA_BOOTSTRAP_SERVERS:}") String brokers,
            @Value("${PROVIDER_KAFKA_TOPIC:}") String topic) {
        this.events=events;
        this.json=json;
        this.brokers=brokers;
        this.topic=topic;
    }

    /** Provider reads Source and publishes directly. Foundation never handles the event payload. */
    public Receipt publish(long id) {
        if (brokers.isBlank() || !topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}"))
            throw new Unavailable();
        var event=events.findById(id);
        if (event==null) throw new IllegalArgumentException("事件不存在");
        final String payload;
        try { payload=json.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new Unavailable(); }
        var config=new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG,"all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,3000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5000);
        try (var producer=new KafkaProducer<String,String>(config)) {
            var result=producer.send(new ProducerRecord<>(topic,event.containerNo(),payload)).get(6,TimeUnit.SECONDS);
            return new Receipt(result.topic(),result.partition(),result.offset());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (ExecutionException | TimeoutException | IllegalArgumentException ex) {
            throw new Unavailable();
        }
    }
}
