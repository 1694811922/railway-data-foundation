package cn.railway.provider;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.http.*;
import java.time.Duration;

/** Validates real workspace sessions; never accepts an operator token from the browser. */
@Component
public class ProducerWorkspaceGuard {
    public record Admission(String userId,String username,String subjectId,String resourceId,String topic) {}
    private final CapabilityRuntimeGuard runtime;private final Environment env;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ProducerWorkspaceGuard(CapabilityRuntimeGuard runtime,Environment env,ObjectMapper json){this.runtime=runtime;this.env=env;this.json=json;}
    public Admission require(String capability,HttpServletRequest request,boolean write){
        if(capability==null||!capability.matches("CAP_[a-f0-9]{32}"))throw rejected(400,"能力编号不合法");
        String cookie=null;if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("JSESSIONID")&&c.getValue().matches("[A-Za-z0-9._-]{10,256}"))cookie="JSESSIONID="+c.getValue();
        if(cookie==null)throw rejected(401,"请登录提供方账号后操作");
        var base=runtime.foundationBase();
        if(!"https".equalsIgnoreCase(base.getScheme())&&!java.util.Set.of("127.0.0.1","localhost","[::1]").contains(base.getHost()))throw rejected(503,"远程底座身份校验必须使用HTTPS");
        try{
            var builder=HttpRequest.newBuilder(base.resolve("/api/v1/capabilities/"+capability+"/producer-context")).timeout(Duration.ofSeconds(4)).header("Cookie",cookie);
            if(write){var csrf=request.getHeader("X-CSRF-TOKEN");if(csrf==null||csrf.length()>512)throw rejected(403,"请求校验失效，请刷新重试");builder.header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody());}else builder.GET();
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var in=response.body()){bytes=in.readNBytes(65537);}
            if(response.statusCode()==401)throw rejected(401,"登录已失效，请重新登录");
            if(response.statusCode()==403)throw rejected(403,"无权操作该能力，或能力当前不可发送");
            if(response.statusCode()!=200||bytes.length>65536)throw rejected(503,"底座生产准入校验暂不可用");
            var body=json.readTree(bytes);var data=body.path("data");var state=data.path("runtime");
            if(body.path("code").asInt(-1)!=0||!data.path("user_id").asText().matches("USR_[a-f0-9]{32}")||data.path("username").asText().isBlank()
                ||!data.path("subject_id").asText().matches("SUB_[a-f0-9]{32}")||!capability.equals(state.path("capability_id").asText())
                ||!state.path("enabled").isBoolean()||!state.path("enabled").booleanValue()||!"ENABLED".equals(state.path("status").asText())
                ||!"KAFKA_TOPIC".equals(state.path("type").asText())||!state.path("resource_id").asText().matches("RES_[a-f0-9]{32}"))throw rejected(503,"底座生产准入响应无效");
            if(!runtime.sourceHost().equalsIgnoreCase(state.path("source_host").asText())||runtime.sourcePort()!=state.path("source_port").asInt(-1)
                ||!runtime.databaseName().equals(state.path("database_name").asText())||!"container_event".equals(state.path("physical_table").asText())
                ||!env.getProperty("PROVIDER_KAFKA_TOPIC","").equals(state.path("topic").asText())
                ||!env.getProperty("PROVIDER_KAFKA_AUTH_TYPE","NONE").equals(state.path("auth_type").asText()))throw rejected(403,"该能力与当前提供服务的来源或主题不匹配");
            return new Admission(data.path("user_id").asText(),data.path("username").asText(),data.path("subject_id").asText(),state.path("resource_id").asText(),state.path("topic").asText());
        }catch(CapabilityRuntimeGuard.Rejected e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw rejected(503,"生产准入校验被中断");}catch(Exception e){throw rejected(503,"底座生产准入校验暂不可用");}
    }
    static CapabilityRuntimeGuard.Rejected rejected(int status,String text){return new CapabilityRuntimeGuard.Rejected(HttpStatus.valueOf(status),status==401?10101:status==403?10102:10006,text);}
}
