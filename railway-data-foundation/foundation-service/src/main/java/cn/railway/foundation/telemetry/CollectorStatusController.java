package cn.railway.foundation.telemetry;
import cn.railway.foundation.security.Actor;
import cn.railway.foundation.api.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** Administrator control metadata only; never forwards business requests or browser-supplied tokens/URLs. */
@RestController
public class CollectorStatusController {
    private final Environment env;private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CollectorStatusController(Environment env,ObjectMapper mapper){this.env=env;this.mapper=mapper;}
    @GetMapping("/api/v1/runtime-collector") public ApiResponse<JsonNode> get(){
        Actor.requireAdmin();
        try{
            URI base=URI.create(env.getRequiredProperty("PROVIDER_REST_BASE_URL"));
            if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/"))||!java.util.Set.of("http","https").contains(base.getScheme()))throw new IllegalArgumentException();
            var response=http.send(HttpRequest.newBuilder(base.resolve("/api/v1/provider/telemetry")).timeout(Duration.ofSeconds(3)).header("X-Provider-Token",env.getRequiredProperty("PROVIDER_CONTROL_TOKEN")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var body=mapper.readTree(response.body());if(response.statusCode()!=200||body.path("code").asInt(-1)!=0)throw new IllegalArgumentException();
            // Allowlist operational counters. Unrecognized responses cannot expose credentials/configuration.
            var safe=mapper.createObjectNode();for(String key:java.util.List.of("enabled","storage_ready","last_success_at","queued_events","staged_reports","submitted_events","rejected_reports","delivered_reports","pending_reports","quarantined_reports","dropped_events","spool_failures","delivery_failures"))if(body.path("data").has(key))safe.set(key,body.path("data").get(key));
            return ApiResponse.success(safe);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"采集状态暂不可用");}
        catch(Exception e){throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"采集状态暂不可用");}
    }
}
