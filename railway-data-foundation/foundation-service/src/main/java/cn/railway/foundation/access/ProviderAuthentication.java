package cn.railway.foundation.access;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ProviderAuthentication {
    private final String configured;
    public ProviderAuthentication(Environment environment){configured=environment.getProperty("PROVIDER_CONTROL_TOKEN","");}
    public void require(String token){
        if(!configured.matches("[A-Za-z0-9_-]{32,128}"))throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"执行服务认证尚未配置");
        if(token==null||token.length()>128||!MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(ErrorCode.ACCESS_FORBIDDEN,"执行服务认证失败");
    }
}
