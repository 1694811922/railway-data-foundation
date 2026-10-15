package cn.railway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AccessRuntimeGuardTest {
    static final String CAP="CAP_"+"1".repeat(32),REL="REL_"+"2".repeat(32),CONTROL="test_"+"c".repeat(43),AUTH="Bearer rdf_"+"a".repeat(43);
    final ObjectMapper json=new ObjectMapper();
    AccessRuntimeGuard guard(String base,String control){var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/port_source_test").withProperty("PROVIDER_FOUNDATION_BASE_URL",base).withProperty("PROVIDER_CONTROL_TOKEN",control);return new AccessRuntimeGuard(new CapabilityRuntimeGuard(env,json),env,json);}
    Map<String,Object> allowed(){
        var data=new LinkedHashMap<String,Object>();data.put("allowed",true);data.put("reason_code","ALLOWED");data.put("reason","允许");data.put("capability_id",CAP);data.put("relationship_id",REL);data.put("subject_id","SUB_"+"3".repeat(32));data.put("resource_id","RES_"+"4".repeat(32));data.put("policy_id","POL_"+"5".repeat(32));data.put("relationship_version",4);data.put("policy_version",1);
        data.put("allowed_fields",List.of("container_no","event_time"));data.put("filter_fields",List.of());data.put("time_field","event_time");data.put("data_from","2026-09-20T00:00:00Z");data.put("data_until","2026-09-21T00:00:00Z");data.put("checked_at",Instant.now().toString());data.put("valid_until",Instant.now().plusSeconds(60).toString());data.put("source_host","127.0.0.1");data.put("source_port",13306);data.put("database_name","port_source_test");data.put("physical_table","container_event");return data;
    }
    void rejected(AccessRuntimeGuard guard,HttpStatus expected){assertThatThrownBy(()->guard.require(CAP,REL,AUTH,null,List.of(),null,null)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,e->assertThat(e.status()).isEqualTo(expected));}
    @Test void missingIdentityAndMisconfiguredServiceTokenFailBeforeNetwork(){
        var guard=guard("http://127.0.0.1:1",CONTROL);
        assertThatThrownBy(()->guard.require(CAP,REL,null,null,List.of(),null,null)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,e->assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(()->guard.require(CAP,REL,"Bearer SUB_"+"3".repeat(32),null,List.of(),null,null)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,e->assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        rejected(guard("http://127.0.0.1:1",""),HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(()->guard.require(CAP,REL,AUTH,List.of("*"),List.of(),null,null)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,e->assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }
    @Test void malformedOrWidenedDecisionsNeverBecomeAnAllowedScope()throws Exception{
        var response=new AtomicReference<Map<String,Object>>(allowed());var body=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/access/check",exchange->{
            assertThat(CONTROL.equals(exchange.getRequestHeaders().getFirst("X-Provider-Token"))).isTrue();assertThat(AUTH.equals(exchange.getRequestHeaders().getFirst("Authorization"))).isTrue();body.set(json.readTree(exchange.getRequestBody()));
            byte[] bytes=json.writeValueAsBytes(Map.of("code",0,"data",response.get()));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var guard=guard("http://127.0.0.1:"+server.getAddress().getPort(),CONTROL);var scope=guard.require(CAP,REL,AUTH,null,List.of(),null,null);assertThat(scope.fields()).containsExactly("container_no","event_time");assertThat(body.get().size()).isEqualTo(6);assertThat(body.get().has("subject_id")).isFalse();
            for(var patch:List.of(Map.of("capability_id","CAP_"+"a".repeat(32)),Map.of("relationship_id","REL_"+"a".repeat(32)),Map.of("allowed_fields",List.of("*")),Map.of("allowed_fields",List.of("container_no","container_no")),Map.of("allowed_fields",List.of("secret_field")),Map.of("time_field","container_no"),Map.of("filter_fields",List.of("train_no")),Map.of("data_until","2025-01-01T00:00:00Z"),Map.of("checked_at","2025-01-01T00:00:00Z"),Map.of("valid_until","2025-01-01T00:00:00Z"),Map.of("policy_version",0))){
                var next=allowed();next.putAll(patch);response.set(next);rejected(guard,HttpStatus.SERVICE_UNAVAILABLE);
            }
            var wrong=allowed();wrong.put("database_name","other_source");response.set(wrong);rejected(guard,HttpStatus.FORBIDDEN);
            response.set(allowed());assertThatThrownBy(()->guard.require(CAP,REL,AUTH,List.of("container_no"),List.of(),null,null)).isInstanceOf(CapabilityRuntimeGuard.Rejected.class);
            assertThatThrownBy(()->guard.require(CAP,REL,AUTH,null,List.of(),Instant.parse("2026-09-20T01:00:00Z"),null)).isInstanceOf(CapabilityRuntimeGuard.Rejected.class);
            var denied=allowed();denied.put("allowed",false);denied.put("reason_code","IDENTITY_INVALID");response.set(denied);rejected(guard,HttpStatus.UNAUTHORIZED);
        }finally{server.stop(0);}
    }
    @Test void redirectsAreNotFollowedAndConnectionFailureIsDenied()throws Exception{
        var hits=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/access/check",exchange->{exchange.getResponseHeaders().add("Location","/leak");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/leak",exchange->{hits.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        var guard=guard("http://127.0.0.1:"+server.getAddress().getPort(),CONTROL);
        try{rejected(guard,HttpStatus.SERVICE_UNAVAILABLE);assertThat(hits.get()).isZero();}finally{server.stop(0);}rejected(guard,HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Test void sourceTimeConversionUsesExplicitFixedOffsetAndRejectsInvalidOffsets(){
        var time=new SourceTime(new MockEnvironment());assertThat(time.local(Instant.parse("2026-09-20T00:10:00Z"))).isEqualTo(java.time.LocalDateTime.parse("2026-09-20T08:10:00"));
        for(String value:List.of("Asia/Shanghai","+18:00","+08:00:01","SQL"))assertThatThrownBy(()->new SourceTime(new MockEnvironment().withProperty("PROVIDER_SOURCE_TIME_OFFSET",value))).isInstanceOf(IllegalStateException.class);
    }
}
