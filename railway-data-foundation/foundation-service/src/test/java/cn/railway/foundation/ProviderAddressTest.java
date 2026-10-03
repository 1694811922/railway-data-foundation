package cn.railway.foundation;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.capability.CapabilityService;
import cn.railway.foundation.capability.RestCapabilityVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderAddressTest {
    private final CapabilityService.Capability capability=mock(CapabilityService.Capability.class);
    private final MockEnvironment environment=new MockEnvironment();
    private final RestCapabilityVerifier verifier=new RestCapabilityVerifier(environment,null);

    @Test void usesOperatorAddressAndNormalizesRootWithoutNetworkAccess() {
        when(capability.protocol()).thenReturn("HTTP");
        environment.setProperty("PROVIDER_REST_BASE_URL","http://127.0.0.1:18081");
        assertThat(verifier.providerBase(capability).resolve("/containers/events").toString())
                .isEqualTo("http://127.0.0.1:18081/containers/events");
        when(capability.protocol()).thenReturn("HTTPS");
        environment.setProperty("PROVIDER_REST_BASE_URL","https://provider.example.org/");
        assertThat(verifier.providerBase(capability).toString()).isEqualTo("https://provider.example.org/");
    }

    @Test void rejectsCredentialsPathsQueriesFragmentsAndUnsupportedProtocolsWithoutEchoingConfiguration() {
        when(capability.protocol()).thenReturn("HTTP");
        for (String address:new String[]{"http://user:secret@localhost", "http://localhost/private", "http://localhost?token=secret",
                "http://localhost#secret", "file:///etc/passwd", "http://localhost:0", "http://localhost:65536", "//localhost", "bad URI"}) {
            environment.setProperty("PROVIDER_REST_BASE_URL",address);
            assertThatThrownBy(()->verifier.providerBase(capability)).isInstanceOf(BusinessException.class)
                    .hasMessage("Provider地址配置不合法或协议不匹配");
        }
    }

    @Test void reportsMissingConfigurationAndProtocolMismatch() {
        when(capability.protocol()).thenReturn("HTTPS");
        assertThatThrownBy(()->verifier.providerBase(capability)).isInstanceOf(BusinessException.class)
                .hasMessage("Provider地址尚未配置");
        environment.setProperty("PROVIDER_REST_BASE_URL","http://localhost:18081");
        assertThatThrownBy(()->verifier.providerBase(capability)).isInstanceOf(BusinessException.class)
                .hasMessage("Provider地址配置不合法或协议不匹配");
    }
}
