package cn.railway.foundation.credential;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Only internal callers can resolve secrets. No credential HTTP endpoint or secret-bearing DTO. */
@Service
public class EncryptedCredentialService implements CredentialService {
    private final JdbcTemplate db;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();
    public EncryptedCredentialService(JdbcTemplate db, Environment environment) { this.db=db; this.environment=environment; }

    @Override public String saveCredential(String existingRef, String username, String password) {
        String ref = existingRef == null ? "CRED_" + UUID.randomUUID().toString().replace("-", "") : existingRef;
        String encrypted = encrypt(ref, password);
        if (existingRef == null) {
            db.update("INSERT INTO source_credential(credential_ref,username,encrypted_value,updated_at) VALUES(?,?,?,?)",
                    ref, username, encrypted, LocalDateTime.now(ZoneOffset.UTC));
        } else if (db.update("UPDATE source_credential SET username=?,encrypted_value=?,updated_at=? WHERE credential_ref=?",
                username, encrypted, LocalDateTime.now(ZoneOffset.UTC), ref) != 1) throw unavailable();
        return ref;
    }

    @Override public SourceCredential getCredential(String ref) {
        if (ref == null) throw unavailable();
        var rows=db.query("SELECT username,encrypted_value FROM source_credential WHERE credential_ref=?",
                (rs,n)->new SourceCredential(rs.getString(1),decrypt(ref,rs.getString(2))), ref);
        if (rows.size()!=1) throw unavailable();
        return rows.get(0);
    }

    @Override public void updateUsername(String ref, String username) {
        if (db.update("UPDATE source_credential SET username=?,updated_at=? WHERE credential_ref=?",
                username,LocalDateTime.now(ZoneOffset.UTC),ref)!=1) throw unavailable();
    }
    @Override public void deleteCredential(String ref) {
        // Referenced credentials are protected by the source_system foreign key.
        db.update("DELETE FROM source_credential WHERE credential_ref=?",ref);
    }
    @Override public SourceCredential fromEnvironment(String prefix) {
        if (prefix==null || !prefix.matches("SOURCE_[A-Z][A-Z0-9_]*")) throw unavailable();
        String username=environment.getProperty(prefix+"_USER"), password=environment.getProperty(prefix+"_PASSWORD");
        if (username==null || username.isBlank() || password==null || password.isBlank())
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"数据源环境变量缺失，请检查连接配置");
        return new SourceCredential(username,password);
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes=Base64.getDecoder().decode(environment.getProperty("FOUNDATION_CREDENTIAL_KEY", ""));
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "凭据加密主密钥未配置或格式不正确，请检查服务端配置");
        }
    }

    /** Internal-only envelope, separate AAD namespace from database source credentials. */
    public String sealSubscription(String grantId,String username,String password) {
        return encrypt(subscriptionContext(grantId,username),password);
    }
    public String openSubscription(String grantId,String username,String stored) {
        return decrypt(subscriptionContext(grantId,username),stored);
    }
    private static String subscriptionContext(String grantId,String username) {
        if(!grantId.matches("KSG_[a-f0-9]{32}")||!username.matches("rdfk_[a-f0-9]{32}"))throw unavailable();
        return "kafka-subscription:"+grantId+":"+username;
    }

    String encrypt(String ref, String password) {
        var key=key();
        byte[] nonce=new byte[12]; random.nextBytes(nonce);
        byte[] plaintext=password.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
            return "v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
        } catch (GeneralSecurityException ex) { throw unavailable(); }
        finally { Arrays.fill(plaintext,(byte)0); }
    }

    String decrypt(String ref, String stored) {
        var key=key();
        try {
            String[] parts=stored.split(":",-1);
            if (parts.length!=3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            byte[] nonce=Base64.getDecoder().decode(parts[1]);
            if (nonce.length!=12) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128,nonce));
            cipher.updateAAD(ref.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext=cipher.doFinal(Base64.getDecoder().decode(parts[2]));
            try { return new String(plaintext,StandardCharsets.UTF_8); }
            finally { Arrays.fill(plaintext,(byte)0); }
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw unavailable(); }
    }
    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.BUSINESS_ERROR,"数据源凭据不可用，请检查凭据与加密主密钥");
    }
}
