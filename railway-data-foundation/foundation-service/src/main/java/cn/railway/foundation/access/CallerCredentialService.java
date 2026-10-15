package cn.railway.foundation.access;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** High-entropy caller tokens, separate from source database passwords. Never log token arguments. */
@Service
public class CallerCredentialService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Credential(String credentialId,String subjectId,String subjectName,String credentialName,
                             String status,Instant expiresAt,int version,Instant createdAt,Instant revokedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Issued(Credential credential,String token) {
        @Override public String toString() { return "Issued[credential="+credential+",token=REDACTED]"; }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(String action,String operatorName,String reason,int version,Instant occurredAt) {}
    public record Identity(String subjectId,Instant expiresAt) {}
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String SELECT="SELECT c.*,s.subject_name FROM caller_credential c JOIN sharing_subject s ON s.subject_id=c.subject_id ";
    private final JdbcTemplate db;
    public CallerCredentialService(JdbcTemplate db) { this.db=db; }

    public List<Credential> list(String subjectId,int limit,int offset) {
        if(!Actor.admin()){if(subjectId!=null&&!Actor.subject().equals(subjectId))Actor.deny();subjectId=Actor.subject();}
        if(subjectId==null)return db.query(SELECT+"ORDER BY c.created_at DESC,c.credential_id DESC LIMIT ? OFFSET ?",(r,n)->row(r),limit,offset);
        return db.query(SELECT+"WHERE c.subject_id=? ORDER BY c.created_at DESC,c.credential_id DESC LIMIT ? OFFSET ?",(r,n)->row(r),subjectId,limit,offset);
    }
    public Credential get(String id) {
        var rows=db.query(SELECT+"WHERE c.credential_id=?",(r,n)->row(r),id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"调用凭据不存在");
        return rows.get(0);
    }
    public List<Event> events(String id) {
        get(id);
        return db.query("SELECT * FROM caller_credential_event WHERE credential_id=? ORDER BY version",
                (r,n)->new Event(r.getString("action"),r.getString("operator_name"),r.getString("reason"),r.getInt("version"),time(r,"occurred_at")),id);
    }
    @Transactional
    public Issued issue(String subjectId,String name,Instant expires,String reason) {
        var subjects=db.query("SELECT subject_type,status FROM sharing_subject WHERE subject_id=? FOR UPDATE",(r,n)->List.of(r.getString(1),r.getString(2)),subjectId);
        if(subjects.isEmpty()||!subjects.get(0).equals(List.of("DEMAND","ENABLED")))invalid("仅可向已启用的需求方签发调用凭据");
        var now=now();if(expires==null||!expires.isAfter(now))invalid("凭据到期时间必须晚于当前时间");
        sql(expires);
        byte[] random=new byte[32];RANDOM.nextBytes(random);
        String token="rdf_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random,(byte)0);
        String id="AUTH_"+UUID.randomUUID().toString().replace("-","");
        try {
            db.update("INSERT INTO caller_credential(credential_id,subject_id,credential_name,token_digest,status,expires_at,version,created_at) VALUES(?,?,?,?,'ENABLED',?,1,?)",
                    id,subjectId,name.trim(),digest(token),sql(expires),sql(now));
        } catch(DuplicateKeyException e){invalid("同一需求方的凭据名称已存在，请使用新的名称");}
        event(id,"ISSUE",reason,1,now);
        return new Issued(get(id),token);
    }
    @Transactional
    public Credential revoke(String id,int version,String reason) {
        var versions=db.query("SELECT version FROM caller_credential WHERE credential_id=? FOR UPDATE",(r,n)->r.getInt(1),id);
        if(versions.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"调用凭据不存在");
        if(versions.get(0)!=version)invalid("凭据已变化，请刷新后重新操作");
        var current=get(id);if(!"ENABLED".equals(current.status()))invalid("凭据已撤销，不能再次撤销或恢复");
        var now=now();
        if(db.update("UPDATE caller_credential SET status='REVOKED',revoked_at=?,version=version+1 WHERE credential_id=? AND version=?",sql(now),id,version)!=1)invalid("凭据已变化，请刷新");
        event(id,"REVOKE",reason,version+1,now);
        return get(id);
    }
    /** Called inside the access decision transaction; never return the stored digest. */
    public Identity authenticate(String authorization,Instant now) {
        if(authorization==null||!authorization.matches("Bearer rdf_[A-Za-z0-9_-]{43}"))return null;
        String token=authorization.substring(7);
        var rows=db.query("SELECT c.subject_id,c.expires_at,c.status,s.status AS subject_status,s.subject_type FROM caller_credential c JOIN sharing_subject s ON s.subject_id=c.subject_id WHERE c.token_digest=?",
                (r,n)->new Object[]{r.getString(1),time(r,"expires_at"),r.getString(3),r.getString(4),r.getString(5)},digest(token));
        if(rows.isEmpty())return null;var row=rows.get(0);Instant expiry=(Instant)row[1];
        if(!"ENABLED".equals(row[2])||!"ENABLED".equals(row[3])||!"DEMAND".equals(row[4])||!expiry.isAfter(now))return null;
        return new Identity((String)row[0],expiry);
    }
    private void event(String id,String action,String reason,int version,Instant now) {
        db.update("INSERT INTO caller_credential_event(credential_id,action,operator_name,operator_user_id,reason,version,occurred_at) VALUES(?,?,?,?,?,?,?)",id,action,Actor.operator(),Actor.userId(),reason.trim(),version,sql(now));
    }
    private static String digest(String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("调用凭据摘要不可用");}
    }
    private static Credential row(ResultSet r)throws SQLException {
        return new Credential(r.getString("credential_id"),r.getString("subject_id"),r.getString("subject_name"),r.getString("credential_name"),r.getString("status"),time(r,"expires_at"),r.getInt("version"),time(r,"created_at"),time(r,"revoked_at"));
    }
    static Instant time(ResultSet r,String key)throws SQLException {var value=r.getTimestamp(key);return value==null?null:value.toLocalDateTime().toInstant(ZoneOffset.UTC);}
    static LocalDateTime sql(Instant value){
        try{var t=LocalDateTime.ofInstant(value,ZoneOffset.UTC);if(t.getYear()<1000||t.getYear()>9999||value.getNano()%1000000!=0)invalid("时间范围不合法或超过毫秒精度");return t;}
        catch(DateTimeException e){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"时间格式不合法");}
    }
    static Instant now(){return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private static void invalid(String message){throw new BusinessException(ErrorCode.BUSINESS_ERROR,message);}
}
