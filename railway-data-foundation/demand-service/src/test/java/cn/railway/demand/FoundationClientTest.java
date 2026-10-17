package cn.railway.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class FoundationClientTest {
    HttpServer server;FoundationClient client;
    final AtomicInteger status=new AtomicInteger(401),requests=new AtomicInteger();
    final AtomicReference<String> authorization=new AtomicReference<>(),cookie=new AtomicReference<>();
    final String grant="KSG_"+"a".repeat(32),token="rkc_"+"x".repeat(43);
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/",exchange->{try{
            requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getRequestBody().readAllBytes();byte[] body="{\"code\":0,\"data\":{\"accepted\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);
        }finally{exchange.close();}});server.start();
        client=new FoundationClient(new MockEnvironment().withProperty("DEMAND_FOUNDATION_URL","http://127.0.0.1:"+server.getAddress().getPort()),new ObjectMapper());
    }
    @AfterEach void stop(){server.stop(0);}
    MockHttpServletRequest session(String method){var r=new MockHttpServletRequest(method,"/api/v1/receiving-tasks");r.setCookies(new Cookie("JSESSIONID","test-session-cookie"));r.addHeader("X-CSRF-TOKEN","test-csrf");return r;}
    @Test void expiredWorkerTokenIsForbiddenNotBrowserUnauthenticated(){
        assertThatThrownBy(()->client.worker(grant,token)).isInstanceOfSatisfying(ReceiverFailure.class,e->{assertThat(e.status).isEqualTo(403);assertThat(e.getMessage()).contains("订阅技术凭据已失效").doesNotContain(token);});
        assertThat(authorization.get()).isEqualTo("Bearer "+token);assertThat(cookie.get()).isNull();
    }
    @Test void malformedWorkerTokenIsRejectedWithoutRequestOrLoginExpiry(){
        for(String value:new String[]{null,"","bad"})assertThatThrownBy(()->client.worker(grant,value)).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(403));assertThat(requests).hasValue(0);
    }
    @Test void expiredSummaryTokenAlsoDoesNotExpireBrowserSession(){
        assertThatThrownBy(()->client.report(grant,token,"{}")).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(403));
    }
    @Test void expiredRealSessionRemains401ForReadsAndWrites(){
        for(String method:new String[]{"GET","POST"})assertThatThrownBy(()->client.identity(session(method))).isInstanceOfSatisfying(ReceiverFailure.class,e->{assertThat(e.status).isEqualTo(401);assertThat(e.getMessage()).contains("登录已失效");});
        assertThatThrownBy(()->client.context(grant,session("POST"))).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(401));
    }
    @Test void missingLoginRemains401AndDoesNotCallUpstream(){
        assertThatThrownBy(()->client.identity(new MockHttpServletRequest("GET","/"))).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(401));assertThat(requests).hasValue(0);
    }
    @Test void accessDenialOutageAndSuccessfulCheckKeepTheirMeaning(){
        for(int code:new int[]{403,503}){status.set(code);assertThatThrownBy(()->client.worker(grant,token)).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(code));}
        status.set(200);assertThat(client.worker(grant,token).path("accepted").asBoolean()).isTrue();
    }
}
