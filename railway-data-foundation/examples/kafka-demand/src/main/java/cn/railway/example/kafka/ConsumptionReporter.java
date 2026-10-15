package cn.railway.example.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Optional metadata-only reporting. Kafka credentials are never sent to Foundation. */
public final class ConsumptionReporter {
    private final URI endpoint;private final String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper();
    public record Position(long partition,long offset,long byte_count){}
    public record Batch(String report_id,String started_at,String completed_at,List<Position> positions){}
    public ConsumptionReporter(String base,String grant,String token){
        URI uri;
        try{uri=URI.create(base);}catch(Exception ex){throw new IllegalArgumentException("摘要接收地址不合法");}
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath())||
           !("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost()))||
           grant==null||!grant.matches("KSG_[a-f0-9]{32}")||token==null||!token.matches("rkc_[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("消费摘要须使用明确底座地址、授权编号及独立上报令牌；远程须HTTPS");
        this.endpoint=uri.resolve("/api/v1/kafka-subscriptions/"+grant+"/consumption-reports");this.token=token;
    }
    @Override public String toString(){return "Consumption reporter [REDACTED]";}
    public boolean send(Batch batch){
        // At most two transport attempts; both use the exact same report and ID. Never re-poll business data.
        try{
            String body=json.writeValueAsString(batch);long bytes=batch.positions().stream().mapToLong(Position::byte_count).sum();
            for(int attempt=0;attempt<2;attempt++){
                try{
                    var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] payload;
                    try(var stream=response.body()){payload=stream.readNBytes(65537);}
                    if(response.statusCode()>=500){continue;}
                    if(response.statusCode()!=200||payload.length>65536)return false;
                    var envelope=json.readTree(payload);var data=envelope.path("data");
                    return envelope.path("code").isInt()&&envelope.path("code").asInt()==0&&batch.report_id().equals(data.path("report_id").asText())&&
                        data.path("records").isIntegralNumber()&&data.path("records").asLong()==batch.positions().size()&&data.path("bytes").isIntegralNumber()&&data.path("bytes").asLong()==bytes&&data.path("replayed").isBoolean();
                }catch(InterruptedException ex){Thread.currentThread().interrupt();return false;}
                catch(java.io.IOException ex){/* Unknown receipt: retry this report, never generate another ID. */}
            }
        }catch(Exception ex){/* Only fixed failure text is emitted by the caller; no payload or credentials in logs. */}
        return false;
    }
}
