package cn.railway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRuntimeGuardTest {
    private static final String ID="CAP_0123456789abcdef0123456789abcdef";
    private CapabilityRuntimeGuard guard(String base) {
        var environment=new MockEnvironment().withProperty("PROVIDER_FOUNDATION_BASE_URL",base)
                .withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/port_source_test?sslMode=DISABLED");
        return new CapabilityRuntimeGuard(environment,new ObjectMapper());
    }

    @Test void rejectsCredentialsAndNonHttpOrPathBasedFoundationAddresses() {
        for (String base:new String[]{"file:///tmp/runtime","http://user:secret@localhost:18080",
                "http://localhost:18080/private","http://localhost:18080/?target=other","http://localhost:18080/#fragment"})
            assertThatThrownBy(()->guard(base)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Provider底座地址或来源地址配置不合法");
    }

    @Test void malformedCapabilityIdsAreRejectedBeforeAnyNetworkCall() {
        var guard=guard("http://127.0.0.1:1");
        for (String id:new String[]{null,"","CAP_1","../other","CAP_0123456789abcdef0123456789abcdef?x=1"})
            assertThatThrownBy(()->guard.requireEnabled(id)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,
                    ex->assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void redirectsAreNotFollowedAndNetworkFailureDoesNotAllowDataAccess() throws Exception {
        var redirected=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/capabilities/",exchange->{
            exchange.getResponseHeaders().add("Location","/unexpected");
            exchange.sendResponseHeaders(302,-1);exchange.close();
        });
        server.createContext("/unexpected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try {
            var guard=guard("http://127.0.0.1:"+server.getAddress().getPort());
            assertThatThrownBy(()->guard.requireEnabled(ID)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,
                    ex->assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(redirected.get()).isZero();
            server.stop(0);
            assertThatThrownBy(()->guard.requireEnabled(ID)).isInstanceOfSatisfying(CapabilityRuntimeGuard.Rejected.class,
                    ex->assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        } finally { server.stop(0); }
    }
}
