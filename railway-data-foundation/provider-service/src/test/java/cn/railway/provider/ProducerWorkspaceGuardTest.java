package cn.railway.provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.InetSocketAddress;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProducerWorkspaceGuardTest {
    final String cap="CAP_"+"a".repeat(32);HttpServer server;int status=200;Map<String,Object> state;String cookie,csrf,method,operator;
    ProducerWorkspaceGuard guard;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        state=new HashMap<>(Map.of("capability_id",cap,"type","KAFKA_TOPIC","status","ENABLED","enabled",true,"resource_id","RES_"+"b".repeat(32),"source_host","127.0.0.1","source_port",13306,"database_name","port_source_test","physical_table","container_event"));state.put("topic","port.container.event.v1");state.put("auth_type","SASL");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/v1/capabilities/",exchange->{
            method=exchange.getRequestMethod();cookie=exchange.getRequestHeaders().getFirst("Cookie");csrf=exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN");operator=exchange.getRequestHeaders().getFirst("X-Provider-Token");
            byte[] bytes=json.writeValueAsBytes(Map.of("code",0,"data",Map.of("user_id","USR_"+"c".repeat(32),"username","real_provider","subject_id","SUB_"+"d".repeat(32),"runtime",state)));
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        var env=new MockEnvironment().withProperty("PROVIDER_FOUNDATION_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()).withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/port_source_test").withProperty("PROVIDER_KAFKA_TOPIC","port.container.event.v1").withProperty("PROVIDER_KAFKA_AUTH_TYPE","SASL");
        guard=new ProducerWorkspaceGuard(new CapabilityRuntimeGuard(env,json),env,json);
    }
    @AfterEach void close(){server.stop(0);}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setCookies(new Cookie("JSESSIONID","session123456789"),new Cookie("unrelated","do_not_forward"));r.addHeader("X-Provider-Token","forged");r.addHeader("X-Subject-Id","forged");return r;}
    void expect(int expected,Runnable work){assertThatThrownBy(work::run).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,e->assertThat(e.status().value()).isEqualTo(expected));}
    @Test void forwardsOnlySessionAndWriteCsrfAndUsesRealIdentity(){var r=request();var a=guard.require(cap,r,false);assertThat(a.username()).isEqualTo("real_provider");assertThat(method).isEqualTo("GET");assertThat(cookie).isEqualTo("JSESSIONID=session123456789");assertThat(operator).isNull();r.addHeader("X-CSRF-TOKEN","fresh-csrf");guard.require(cap,r,true);assertThat(method).isEqualTo("POST");assertThat(csrf).isEqualTo("fresh-csrf");}
    @Test void anonymousInvalidIdAndMissingCsrfNeverReachUpstream(){expect(401,()->guard.require(cap,new MockHttpServletRequest(),false));expect(400,()->guard.require("../other",request(),false));expect(403,()->guard.require(cap,request(),true));assertThat(method).isNull();}
    @Test void preservesAuthenticationAndPermissionErrors(){status=401;expect(401,()->guard.require(cap,request(),false));status=403;expect(403,()->guard.require(cap,request(),false));status=503;expect(503,()->guard.require(cap,request(),false));}
    @Test void refusesEverySourceAndTopicMismatch(){for(String key:List.of("source_host","database_name","physical_table","topic","auth_type")){Object old=state.put(key,"wrong");expect(403,()->guard.require(cap,request(),false));state.put(key,old);}state.put("source_port",3306);expect(403,()->guard.require(cap,request(),false));}
    @Test void rejectsMalformedOrDisabledAdmission(){state.put("enabled",false);expect(503,()->guard.require(cap,request(),false));state.put("enabled",true);state.put("capability_id","CAP_"+"f".repeat(32));expect(503,()->guard.require(cap,request(),false));}
}
