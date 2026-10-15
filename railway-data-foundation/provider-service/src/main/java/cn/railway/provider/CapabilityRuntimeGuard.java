package cn.railway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Checks control metadata for each new REST request, before reading Source business rows. */
@Component
public class CapabilityRuntimeGuard {
    public static final String HEADER = "X-Capability-Id";
    public static final class Rejected extends RuntimeException {
        private final HttpStatus status;
        private final int code;
        Rejected(HttpStatus status, int code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
        public HttpStatus status() { return status; }
        public int code() { return code; }
    }

    private final URI foundation;
    private final URI source;
    private final ObjectMapper json;
    private final Environment environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public CapabilityRuntimeGuard(Environment environment, ObjectMapper json) {
        this.environment=environment;
        this.json = json;
        try {
            foundation = URI.create(environment.getProperty("PROVIDER_FOUNDATION_BASE_URL", "http://127.0.0.1:18080"));
            if (foundation.getHost() == null || foundation.getUserInfo() != null
                    || foundation.getRawQuery() != null || foundation.getRawFragment() != null
                    || !(foundation.getRawPath().isEmpty() || "/".equals(foundation.getRawPath()))
                    || !("http".equalsIgnoreCase(foundation.getScheme()) || "https".equalsIgnoreCase(foundation.getScheme())))
                throw new IllegalArgumentException();
            String jdbc = environment.getRequiredProperty("spring.datasource.url");
            if (!jdbc.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            source = URI.create(jdbc.substring(5));
            if (source.getHost() == null || source.getUserInfo() != null
                    || !source.getPath().matches("/[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Provider底座地址或来源地址配置不合法");
        }
    }

    public void requireEnabled(String capabilityId) {
        require(capabilityId,false);
    }
    public record KafkaAdmission(String capabilityId,String resourceId) {}
    public KafkaAdmission requireKafkaEnabled(String capabilityId) {
        var state=require(capabilityId,true);
        String resourceId=state.path("resource_id").asText();
        if(!resourceId.matches("RES_[a-f0-9]{32}"))throw unavailable();
        return new KafkaAdmission(capabilityId,resourceId);
    }
    private com.fasterxml.jackson.databind.JsonNode require(String capabilityId,boolean kafka) {
        if (capabilityId == null || !capabilityId.matches("CAP_[a-f0-9]{32}"))
            throw new Rejected(HttpStatus.BAD_REQUEST, 10004, "请通过X-Capability-Id请求头提供有效的接口能力编号");
        URI target = foundation.resolve("/api/v1/capabilities/" + capabilityId + (kafka?"/kafka-runtime":"/runtime"));
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).header("X-Provider-Token",environment.getProperty("PROVIDER_CONTROL_TOKEN","")).GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw denied();
            if (response.statusCode() != 200) throw unavailable();
            var envelope = json.readTree(response.body());
            if (envelope == null || !envelope.path("code").isInt() || envelope.path("code").intValue() != 0)
                throw unavailable();
            var runtime = envelope.path("data");
            if (!capabilityId.equals(runtime.path("capability_id").asText())) throw unavailable();
            if (!runtime.path("enabled").isBoolean()) throw unavailable();
            if (!runtime.path("enabled").booleanValue() || !"ENABLED".equals(runtime.path("status").asText()))
                throw denied();
            if(kafka && (!"KAFKA_TOPIC".equals(runtime.path("type").asText())
                    || !environment.getProperty("PROVIDER_KAFKA_TOPIC","").equals(runtime.path("topic").asText())
                    || !environment.getProperty("PROVIDER_KAFKA_AUTH_TYPE","NONE").equals(runtime.path("auth_type").asText())))
                throw new Rejected(HttpStatus.FORBIDDEN,10005,"消息能力的主题或认证方式与当前提供服务不一致");
            int expectedPort = source.getPort() == -1 ? 3306 : source.getPort();
            if (!source.getHost().equalsIgnoreCase(runtime.path("source_host").asText())
                    || !runtime.path("source_port").isInt() || expectedPort != runtime.path("source_port").intValue()
                    || !source.getPath().substring(1).equals(runtime.path("database_name").asText())
                    || !"container_event".equals(runtime.path("physical_table").asText()))
                throw new Rejected(HttpStatus.FORBIDDEN, 10005, "接口能力对应的数据源与当前Provider不一致");
            return runtime;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException ex) { throw unavailable(); }
    }

    String sourceHost() { return source.getHost(); }
    URI foundationBase() { return foundation; }
    int sourcePort() { return source.getPort() == -1 ? 3306 : source.getPort(); }
    String databaseName() { return source.getPath().substring(1); }

    private static Rejected denied() {
        return new Rejected(HttpStatus.FORBIDDEN, 10005, "接口能力未启用，或所属资源及数据源不可用");
    }
    private static Rejected unavailable() {
        return new Rejected(HttpStatus.SERVICE_UNAVAILABLE, 10006, "底座能力状态暂不可用，请稍后重试");
    }
}
