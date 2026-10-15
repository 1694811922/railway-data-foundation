package cn.railway.foundation.capability;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaCapabilityVerifier {
    private final Environment environment;
    private final ProviderSourceBinding binding;
    public KafkaCapabilityVerifier(Environment environment,ProviderSourceBinding binding) {
        this.environment=environment;
        this.binding=binding;
    }

    /** Metadata-only probe: no Kafka consumer and no business payload in Foundation. */
    public boolean probe(CapabilityService.Capability capability) {
        requireConfigured(capability);
        final Properties config;
        try { config=KafkaClientSettings.create(environment,"FOUNDATION_KAFKA_",capability.authType()); }
        catch(IllegalArgumentException e){throw new BusinessException(ErrorCode.BUSINESS_ERROR,e.getMessage());}
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,4000);
        final AdminClient admin;
        try {admin=AdminClient.create(config);} catch(org.apache.kafka.common.KafkaException ex){return false;}
        try {
            var info=admin.describeTopics(List.of(capability.topic())).allTopicNames().get(5,TimeUnit.SECONDS).get(capability.topic());
            return info!=null && !info.partitions().isEmpty()
                    && info.partitions().stream().allMatch(partition->partition.leader()!=null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();return false;
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException ex) {
            return false;
        } finally {admin.close(java.time.Duration.ofSeconds(1));}
    }
    public void requireConfigured(CapabilityService.Capability capability) {
        if (!"KAFKA_TOPIC".equals(capability.type()) || !"KAFKA".equals(capability.protocol())
                || !java.util.Set.of("NONE","SASL").contains(capability.authType()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅支持指定Kafka消息主题的运行时控制");
        String configuredTopic=environment.getProperty("FOUNDATION_KAFKA_TOPIC","");
        if (!configuredTopic.equals(capability.topic()) || configuredTopic.isBlank())
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力 Topic 与 Provider 配置不一致");
        binding.requireSupported(capability.resourceId());
        try {KafkaClientSettings.create(environment,"FOUNDATION_KAFKA_",capability.authType());}
        catch(IllegalArgumentException e){throw new BusinessException(ErrorCode.BUSINESS_ERROR,e.getMessage());}
    }
}
