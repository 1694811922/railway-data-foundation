package cn.railway.foundation.subscription;

import cn.railway.foundation.api.*;
import cn.railway.foundation.security.WorkspaceGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Subscription-scoped self-reported observations. Never connects to a Broker or accepts message bodies. */
@Service
public class ConsumptionService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(String reportId,int records,long bytes,boolean replayed,Instant receivedAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Report(String reportId,String brokerUsername,int grantVersion,Instant startedAt,Instant completedAt,
                         int recordCount,long byteCount,Instant receivedAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(String state,long reports,Long records,Long bytes,Instant lastReceivedAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(String grantId,Summary summary,List<Report> items,long total){}
    private record Stored(String hash,Receipt receipt){}
    void verifyCredential(String id,String authorization){
        String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):"";
        var rows=db.queryForList("SELECT consumption_token_digest,execution_state,desired_state,issued_at FROM kafka_subscription_execution WHERE grant_id=?",id);
        if(!token.matches("rkc_[A-Za-z0-9_-]{43}")||rows.isEmpty()||rows.get(0).get("consumption_token_digest")==null||
           !MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),rows.get(0).get("consumption_token_digest").toString().getBytes(StandardCharsets.US_ASCII)))unauthorized();
        var r=rows.get(0);if(!"ACTIVE".equals(r.get("execution_state"))||!"GRANTED".equals(r.get("desired_state"))||r.get("issued_at")==null)forbidden();
    }
    private final JdbcTemplate db;private final SubscriptionService grants;private final WorkspaceGate gate;private final ObjectMapper json;
    public ConsumptionService(JdbcTemplate db,SubscriptionService grants,WorkspaceGate gate,ObjectMapper json){this.db=db;this.grants=grants;this.gate=gate;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY)
    public String issue(String id){
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String token="rkc_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);
        db.update("UPDATE kafka_subscription_execution SET consumption_token_digest=? WHERE grant_id=?",digest(token),id);
        return token;
    }
    @Transactional
    public Receipt accept(String id,String authorization,ConsumptionReportRequest request){
        // Lock the same parent as claim/stop/revoke: no report may race through a committed stop request.
        var locks=db.query("SELECT grant_id FROM kafka_subscription_grant WHERE grant_id=? FOR UPDATE",(r,n)->r.getString(1),id);
        if(locks.isEmpty())unauthorized();
        String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):"";
        var executions=db.queryForList("SELECT broker_username,consumption_token_digest,execution_state,desired_state,issued_at FROM kafka_subscription_execution WHERE grant_id=?",id);
        if(!token.matches("rkc_[A-Za-z0-9_-]{43}")||executions.isEmpty()||executions.get(0).get("consumption_token_digest")==null||
           !MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),executions.get(0).get("consumption_token_digest").toString().getBytes(StandardCharsets.US_ASCII)))unauthorized();
        var execution=executions.get(0);var grant=grants.get(id);
        if(!"ACTIVE".equals(execution.get("execution_state"))||!"GRANTED".equals(execution.get("desired_state"))||execution.get("issued_at")==null||!grant.brokerAccessConfirmed())forbidden();
        try{grants.requireEligible(grant);}catch(BusinessException ex){forbidden();}
        validateTimes(request,grant);
        var positions=new ArrayList<>(request.positions());
        positions.sort(Comparator.comparing(ConsumptionReportRequest.Position::partition).thenComparing(ConsumptionReportRequest.Position::offset));
        long bytes=0;ConsumptionReportRequest.Position previous=null;
        for(var position:positions){
            if(previous!=null&&previous.partition().equals(position.partition())&&previous.offset().equals(position.offset()))invalid("同一报告不能重复登记分区位点");
            bytes+=position.byteCount();previous=position;
        }
        if(bytes>134217728)invalid("报告字节合计超出限制");
        String positionsJson=serialize(positions),hash=digest(request.reportId()+"|"+request.startedAt()+"|"+request.completedAt()+"|"+execution.get("broker_username")+"|"+positionsJson);
        var old=db.query("SELECT content_digest,record_count,byte_count,received_at FROM kafka_consumption_report WHERE grant_id=? AND report_id=?",(r,n)->new Stored(r.getString(1),new Receipt(request.reportId(),r.getInt(2),r.getLong(3),true,time(r,4))),id,request.reportId());
        if(!old.isEmpty()){
            var row=old.get(0);if(!hash.equals(row.hash()))throw new BusinessException(ErrorCode.VERSION_CONFLICT,"报告编号已存在且内容不同，不得覆盖");
            return row.receipt();
        }
        Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        db.update("INSERT INTO kafka_consumption_report(grant_id,report_id,broker_username,grant_version,started_at,completed_at,record_count,byte_count,positions_json,content_digest,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id,request.reportId(),execution.get("broker_username"),grant.version(),sql(request.startedAt()),sql(request.completedAt()),positions.size(),bytes,positionsJson,hash,sql(now));
        return new Receipt(request.reportId(),positions.size(),bytes,false,now);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String id,int limit,int offset){
        gate.application("kafka_subscription_grant","grant_id",id);grants.get(id);
        var summary=db.queryForObject("SELECT COUNT(*),SUM(record_count),SUM(byte_count),MAX(received_at) FROM kafka_consumption_report WHERE grant_id=?",(r,n)->{
            long total=r.getLong(1);
            return new Summary(total==0?"NOT_REPORTED":"OBSERVED",total,total==0?null:r.getLong(2),total==0?null:r.getLong(3),total==0?null:time(r,4));
        },id);
        long total=summary.reports();
        var items=db.query("SELECT report_id,broker_username,grant_version,started_at,completed_at,record_count,byte_count,received_at FROM kafka_consumption_report WHERE grant_id=? ORDER BY completed_at DESC,report_id DESC LIMIT ? OFFSET ?",(r,n)->new Report(r.getString(1),r.getString(2),r.getInt(3),time(r,4),time(r,5),r.getInt(6),r.getLong(7),time(r,8)),id,limit,offset);
        return new Page(id,summary,items,total);
    }
    private void validateTimes(ConsumptionReportRequest r,SubscriptionService.Grant grant){
        Instant minimum=Instant.parse("2000-01-01T00:00:00Z"),maximum=Instant.now().plusSeconds(5);
        if(r.startedAt().isBefore(minimum)||r.completedAt().isAfter(maximum)||r.startedAt().isAfter(r.completedAt())||Duration.between(r.startedAt(),r.completedAt()).toMillis()>60000||
           r.startedAt().getNano()%1000000!=0||r.completedAt().getNano()%1000000!=0||r.startedAt().isBefore(grant.validFrom())||!r.completedAt().isBefore(grant.validUntil()))invalid("观察时间须为授权期内毫秒精度，单批不超过一分钟且不能在未来");
    }
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"摘要格式不合法");}}
    static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException("摘要算法不可用");}}
    private static LocalDateTime sql(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static Instant time(ResultSet r,int index)throws SQLException{return r.getTimestamp(index).toLocalDateTime().toInstant(ZoneOffset.UTC);}
    private static void unauthorized(){throw new BusinessException(ErrorCode.UNAUTHENTICATED,"消费摘要上报凭据无效");}
    private static void forbidden(){throw new BusinessException(ErrorCode.ACCESS_FORBIDDEN,"订阅已不可用，拒绝消费摘要上报");}
    private static void invalid(String message){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message);}
}
