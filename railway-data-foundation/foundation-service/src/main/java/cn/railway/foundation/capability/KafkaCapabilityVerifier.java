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
        if (!"KAFKA_TOPIC".equals(capability.type()) || !"KAFKA".equals(capability.protocol())
                || !"NONE".equals(capability.authType()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"当前仅支持无认证的 Kafka Topic 元数据验证");
        String configuredTopic=environment.getProperty("FOUNDATION_KAFKA_TOPIC","");
        if (!configuredTopic.equals(capability.topic()) || configuredTopic.isBlank())
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力 Topic 与 Provider 配置不一致");
        binding.requireSupported(capability.resourceId());
        String brokers=environment.getProperty("FOUNDATION_KAFKA_BOOTSTRAP_SERVERS","");
        if (brokers.isBlank()) throw new BusinessException(ErrorCode.BUSINESS_ERROR,"Kafka 地址尚未配置");
        var config=new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,4000);
        try (var admin=AdminClient.create(config)) {
            var info=admin.describeTopics(List.of(configuredTopic)).allTopicNames().get(5,TimeUnit.SECONDS).get(configuredTopic);
            return info!=null && !info.partitions().isEmpty()
                    && info.partitions().stream().allMatch(partition->partition.leader()!=null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | IllegalArgumentException ex) {
            return false;
        }
    }
}
