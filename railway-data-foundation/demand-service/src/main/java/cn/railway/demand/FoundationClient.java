package cn.railway.demand;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;

/** Fixed control upstream only. Never forwards business records or Kafka passwords. */
@Component
public class FoundationClient {
    private final URI base;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public FoundationClient(Environment env,ObjectMapper json){
        this.json=json;base=URI.create(env.getProperty("DEMAND_FOUNDATION_URL","http://127.0.0.1:18080"));
        if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Set.of("","/").contains(base.getPath())||
            !("https".equals(base.getScheme())||"http".equals(base.getScheme())&&Set.of("localhost","127.0.0.1","[::1]").contains(base.getHost())))throw new IllegalStateException("需求方控制地址必须固定且远程使用HTTPS");
    }
    public JsonNode identity(HttpServletRequest r){
        boolean write=!Set.of("GET","HEAD","OPTIONS").contains(r.getMethod());
        var user=call(write?"/api/v1/auth/receiver-session":"/api/v1/auth/me",write,r,null);
        if(!"DEMAND".equals(user.path("role").asText())||user.path("subject_id").asText().isBlank())throw new ReceiverFailure(403,"仅需求方可使用数据接收功能");return user;
    }
    public JsonNode context(String grant,HttpServletRequest r){return call(path(grant)+"/receiver-context",true,r,null);}
    public JsonNode worker(String grant,String token){return call(path(grant)+"/receiver-access/check",true,null,token);}
    public JsonNode report(String grant,String token,String body){return request(path(grant)+"/consumption-reports",true,null,token,body);}
    public JsonNode monitor(String token,String body){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43,128}"))throw new ReceiverFailure(401,"监测技术凭据无效");
        try{
            var request=HttpRequest.newBuilder(base.resolve("/api/v1/telemetry/receiver-reports")).timeout(Duration.ofSeconds(4)).header("X-Receiver-Telemetry-Token",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(65537);}
            if(response.statusCode()!=200)throw new ReceiverFailure(Set.of(400,401,403,404,409).contains(response.statusCode())?response.statusCode():503,"监测事件未确认");
            var reply=json.readTree(bytes);if(bytes.length>65536||reply.path("code").asInt(-1)!=0||!reply.path("data").isObject())throw new ReceiverFailure(503,"监测回执无效");return reply.path("data");
        }catch(ReceiverFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ReceiverFailure(503,"监测上报中断");}catch(Exception e){throw new ReceiverFailure(503,"监测上报暂不可用");}
    }
    private String path(String grant){if(grant==null||!grant.matches("KSG_[a-f0-9]{32}"))throw new ReceiverFailure(400,"授权编号无效");return "/api/v1/kafka-subscriptions/"+grant;}
    private JsonNode call(String path,boolean post,HttpServletRequest r,String token){return request(path,post,r,token,"{}");}
    private JsonNode request(String path,boolean post,HttpServletRequest r,String token,String body){
        try{
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(4));
            if(r!=null){
                String cookie=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("JSESSIONID")&&c.getValue().matches("[A-Za-z0-9._-]{10,256}"))cookie="JSESSIONID="+c.getValue();
                if(cookie==null)throw new ReceiverFailure(401,"请登录后操作");b.header("Cookie",cookie);
                if(post){var csrf=r.getHeader("X-CSRF-TOKEN");if(csrf==null||csrf.length()>512)throw new ReceiverFailure(403,"请求校验失效，请刷新重试");b.header("X-CSRF-TOKEN",csrf);}
            }
            if(r==null||token!=null){if(token==null||!token.matches("rkc_[A-Za-z0-9_-]{43}"))throw subscriptionCredentialFailure();b.header("Authorization","Bearer "+token);}
            if(post)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else b.GET();
            var response=http.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var s=response.body()){bytes=s.readNBytes(1048577);}
            // Upstream service-token authentication is independent of the user's browser session.
            // Only session-authenticated calls may tell the browser to discard its login.
            if(response.statusCode()==401){if(r==null)throw subscriptionCredentialFailure();throw new ReceiverFailure(401,"登录已失效，请重新登录");}
            if(response.statusCode()==403)throw new ReceiverFailure(403,"无权限或订阅已不可用");
            if(response.statusCode()>=500||bytes.length>1048576)throw new ReceiverFailure(503,"底座授权核验暂不可用");
            if(response.statusCode()!=200)throw new ReceiverFailure(400,"授权核验失败，请检查申请与凭据");
            var envelope=json.readTree(bytes);if(envelope.path("code").asInt(-1)!=0||!envelope.path("data").isObject())throw new ReceiverFailure(503,"底座核验响应无效");return envelope.path("data");
        }catch(ReceiverFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ReceiverFailure(503,"授权核验被中断");}catch(Exception e){throw new ReceiverFailure(503,"底座授权核验暂不可用");}
    }
    private static ReceiverFailure subscriptionCredentialFailure(){return new ReceiverFailure(403,"接收任务的订阅技术凭据已失效，请停止任务并更新凭据后恢复");}
}
