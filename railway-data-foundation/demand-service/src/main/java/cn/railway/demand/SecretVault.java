package cn.railway.demand;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.util.*;
import java.nio.charset.StandardCharsets;
@Component
public class SecretVault {
    private final SecretKeySpec key;
    public SecretVault(Environment env){try{byte[] bytes=Base64.getDecoder().decode(env.getRequiredProperty("DEMAND_CREDENTIAL_KEY"));if(bytes.length!=32)throw new IllegalArgumentException();key=new SecretKeySpec(bytes,"AES");Arrays.fill(bytes,(byte)0);}catch(Exception e){throw new IllegalStateException("必须配置需求方独立的32字节加密密钥");}}
    public String seal(String task,String value){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));c.updateAAD(task.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new ReceiverFailure(503,"接收凭据加密失败");}}
    public String open(String task,String value){try{var parts=value.split("\\.");var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));c.updateAAD(task.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(Exception e){throw new ReceiverFailure(400,"接收凭据无法解密，请保留原需求方密钥");}}
}
