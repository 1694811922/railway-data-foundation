package cn.railway.foundation.capability;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class RestCapabilityVerifier {
    private final Environment environment;
    private final ProviderSourceBinding binding;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public RestCapabilityVerifier(Environment environment,ProviderSourceBinding binding) { this.environment=environment; this.binding=binding; }

    /** Only an operator-configured Provider host can be probed. No business response is requested. */
    public boolean probe(CapabilityService.Capability capability) {
        if (!"REST_API".equals(capability.type()) || !"GET".equals(capability.httpMethod())
                || !"NONE".equals(capability.authType()) || !"/containers/events".equals(capability.endpoint()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"当前仅支持集装箱事件REST GET原始适配器的能力验证");
        var source=binding.requireSupported(capability.resourceId());
        URI target=providerBase(capability).resolve(capability.endpoint());
        HttpRequest request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(4))
                .method("HEAD",HttpRequest.BodyPublishers.noBody()).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            return response.statusCode()==204
                    && response.headers().firstValue("X-Railway-Provider").orElse("").equals("container-event-v1")
                    && response.headers().firstValue("X-Railway-Runtime").orElse("").equals("rest-capability-v1")
                    && response.headers().firstValue("X-Railway-Access").orElse("").equals("sharing-access-v1")
                    && source.host().equalsIgnoreCase(response.headers().firstValue("X-Railway-Source-Host").orElse(""))
                    && Integer.toString(source.port()).equals(response.headers().firstValue("X-Railway-Source-Port").orElse(""))
                    && source.databaseName().equals(response.headers().firstValue("X-Railway-Source-Database").orElse(""))
                    && source.physicalTable().equals(response.headers().firstValue("X-Railway-Source-Table").orElse(""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** Display and probing share the same validated operator configuration; never a user-supplied URL. */
    public URI providerBase(CapabilityService.Capability capability) {
        String configured=environment.getProperty("PROVIDER_REST_BASE_URL");
        if (configured==null || configured.isBlank())
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"Provider地址尚未配置");
        final URI base;
        try {
            base=URI.create(configured);
            if (base.getHost()==null || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
                    || !(base.getRawPath().isEmpty() || "/".equals(base.getRawPath()))
                    || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getPort()==0 || base.getPort()>65535
                    || !base.getScheme().equalsIgnoreCase(capability.protocol()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"Provider地址配置不合法或协议不匹配");
        }
        return base.resolve("/");
    }
}
