package cn.railway.example.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ConsumptionReporterTest {
    final String grant="KSG_"+UUID.randomUUID().toString().replace("-",""),token="rkc_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    ConsumptionReporter.Batch batch(){String now=Instant.now().toString();return new ConsumptionReporter.Batch(UUID.randomUUID().toString(),now,now,List.of(new ConsumptionReporter.Position(0,3,320)));}
    @Test void retriesReuseSameMetadataAndIndependentHeader()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();var bodies=new ArrayList<String>();var json=new ObjectMapper();
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+token);
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);bodies.add(body);int attempt=count.incrementAndGet();
            byte[] response=(attempt==1?"{}":json.writeValueAsString(Map.of("code",0,"data",Map.of("report_id",json.readTree(body).path("report_id").asText(),"records",1,"bytes",320,"replayed",true)))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt==1?503:200,response.length);try(var output=exchange.getResponseBody()){output.write(response);}
        });server.start();
        try{assertThat(new ConsumptionReporter("http://127.0.0.1:"+server.getAddress().getPort(),grant,token).send(batch())).isTrue();assertThat(bodies).hasSize(2);assertThat(bodies.get(0)).isEqualTo(bodies.get(1)).doesNotContain(token,"password","key","value","topic","subject_id");}
        finally{server.stop(0);}
    }
    @Test void refusalAndWrongAcknowledgementCannotBecomeSuccess()throws Exception{
        for(int status:List.of(403,302,200)){
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();server.createContext("/",exchange->{count.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="{\"code\":0,\"data\":{\"report_id\":\"wrong\",\"records\":0,\"bytes\":0,\"replayed\":false}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
            try{assertThat(new ConsumptionReporter("http://127.0.0.1:"+server.getAddress().getPort(),grant,token).send(batch())).isFalse();assertThat(count.get()).isEqualTo(1);}finally{server.stop(0);}
        }
    }
    @Test void rejectRemoteHttpCredentialsInUrlsAndOtherCredentialTypes(){
        for(String base:List.of("http://remote.example","https://user:secret@example.test","https://example.test/path","https://example.test?token=x"))assertThatThrownBy(()->new ConsumptionReporter(base,grant,token)).isInstanceOf(IllegalArgumentException.class);
        for(String other:List.of("ks_"+"a".repeat(43),"rdf_"+"a".repeat(43)))assertThatThrownBy(()->new ConsumptionReporter("http://127.0.0.1:18080",grant,other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ConsumptionReporter("https://example.test",grant,token).toString()).doesNotContain(token);
    }
}
