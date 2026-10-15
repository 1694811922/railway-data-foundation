package cn.railway.foundation.subscription;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Approval and execution summaries only; no message reads or credential disclosure. */
@Service
public class SubscriptionService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Grant(String grantId,String grantName,String providerSubjectId,String providerName,
                        String demandSubjectId,String demandName,String resourceId,String resourceName,
                        String capabilityId,String capabilityName,String topic,String authType,String consumerGroup,
                        String scope,String purpose,Instant validFrom,Instant validUntil,String status,int version,
                        Instant confirmedAt,Instant createdAt,Instant updatedAt,String executionState,boolean brokerAccessConfirmed,
                        String brokerUsername,String executionReason,Instant checkedAt,boolean credentialAvailable,boolean executionEnabled,
                        String providerReviewMode,Instant providerConfirmedAt) {}
    public record Page(List<Grant> items,long total) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(long eventId,String grantId,String action,String fromStatus,String toStatus,
                        boolean wholeTopicAcknowledged,String operatorName,String reason,int version,Instant occurredAt,String executionState) {}
    private record Binding(String topic,String authType,String status,String health,String owner) {}
    private static final String SELECT="SELECT g.*,p.subject_name provider_name,d.subject_name demand_name,"+
            "r.resource_name,c.capability_name,p.status provider_status,d.status demand_status,r.status resource_status,s.status source_status,c.status capability_status,c.health_status,c.topic current_topic,c.auth_type current_auth,"+
            "x.execution_state,x.broker_username,x.last_error,x.checked_at,x.issued_at,(x.encrypted_password IS NOT NULL) has_secret FROM kafka_subscription_grant g "+
            "JOIN sharing_subject p ON p.subject_id=g.provider_subject_id "+
            "JOIN sharing_subject d ON d.subject_id=g.demand_subject_id "+
            "JOIN data_resource r ON r.resource_id=g.resource_id JOIN source_system s ON s.source_id=r.source_id JOIN capability c ON c.capability_id=g.capability_id LEFT JOIN kafka_subscription_execution x ON x.grant_id=g.grant_id ";
    private static final Set<String> STATES=Set.of("DRAFT","PENDING","APPROVED","REVOKED");
    private static final Map<String,Map<String,String>> TRANSITIONS=Map.of(
            "submit",Map.of("DRAFT","PENDING"),"confirm",Map.of("PENDING","APPROVED"),
            "return",Map.of("PENDING","DRAFT"),"revoke",Map.of("DRAFT","REVOKED","PENDING","REVOKED","APPROVED","REVOKED"));
    private final JdbcTemplate db;
    private final WorkspaceGate gate;
    private final SubscriptionBroker broker;
    public SubscriptionService(JdbcTemplate db,SubscriptionBroker broker,WorkspaceGate gate) { this.db=db; this.gate=gate;this.broker=broker; }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(int limit,int offset,String keyword,String status) {
        if(!status.isEmpty()&&!STATES.contains(status)) invalid("订阅审批状态不合法");
        // LOCATE is literal: %, _ and backslash in a search are not SQL wildcard patterns.
        String where="WHERE "+Actor.sharing("g")+" AND (?='' OR g.status=?) AND (?='' OR LOCATE(?,CONCAT_WS(' ',g.grant_id,g.grant_name,g.topic,p.subject_name,d.subject_name,r.resource_name,c.capability_name))>0) ";
        Object[] args=Actor.args(status,status,keyword.trim(),keyword.trim());
        long total=db.queryForObject("SELECT COUNT(*) FROM ("+SELECT+where+") counted",Long.class,args);
        var paged=new ArrayList<Object>(Arrays.asList(args));paged.add(limit);paged.add(offset);
        return new Page(db.query(SELECT+where+"ORDER BY g.updated_at DESC,g.grant_id DESC LIMIT ? OFFSET ?",mapper(),paged.toArray()),total);
    }
    public Grant get(String id) {
        var rows=db.query(SELECT+"WHERE g.grant_id=?",mapper(),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"订阅授权不存在");
        return rows.get(0);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Event> events(String id) {
        get(id);
        return db.query("SELECT * FROM kafka_subscription_event WHERE grant_id=? ORDER BY version",(r,n)->
                new Event(r.getLong("event_id"),r.getString("grant_id"),r.getString("action"),r.getString("from_status"),r.getString("to_status"),
                        r.getBoolean("whole_topic_acknowledged"),r.getString("operator_name"),r.getString("reason"),r.getInt("version"),time(r,"occurred_at"),r.getString("execution_state")),id);
    }
    @Transactional
    public Grant create(SubscriptionRequest r) {
        gate.newApplication(r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId());
        if(!Boolean.TRUE.equals(r.wholeTopicAcknowledged())) invalid("必须明确接受完整消息及可访问历史的整主题范围");
        validateRange(r.validFrom(),r.validUntil());
        var binding=lockBinding(r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId());
        String uuid=UUID.randomUUID().toString().replace("-",""),id="KSG_"+uuid;var now=now();
        db.update("INSERT INTO kafka_subscription_grant(grant_id,grant_name,provider_subject_id,demand_subject_id,resource_id,capability_id,topic,auth_type,consumer_group,scope,purpose,valid_from,valid_until,status,version,created_at,updated_at,provider_review_mode) VALUES(?,?,?,?,?,?,?,?,?,'FULL_TOPIC',?,?,?,'DRAFT',1,?,?,?)",
                id,r.grantName().trim(),r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId(),binding.topic(),binding.authType(),"rdf.subscription."+uuid,r.purpose().trim(),sql(r.validFrom()),sql(r.validUntil()),sql(now),sql(now),binding.owner()==null?"ADMIN_MANAGED":"REQUIRED");
        event(id,"CREATE",null,"DRAFT",true,r.reason(),1,now);
        return get(id);
    }
    @Transactional
    public Grant update(String id,SubscriptionUpdateRequest r) {
        var current=locked(id,r.version());
        if(!"DRAFT".equals(current.status())) invalid("仅草稿申请可编辑；确认后必须撤回并重新申请");
        checkBinding(current,false);validateRange(r.validFrom(),r.validUntil());var now=now();
        db.update("UPDATE kafka_subscription_grant SET grant_name=?,purpose=?,valid_from=?,valid_until=?,provider_confirmed_at=NULL,version=version+1,updated_at=? WHERE grant_id=? AND version=?",
                r.grantName().trim(),r.purpose().trim(),sql(r.validFrom()),sql(r.validUntil()),sql(now),id,r.version());
        event(id,"UPDATE","DRAFT","DRAFT",false,r.reason(),r.version()+1,now);return get(id);
    }
    @Transactional
    public Grant action(String id,String action,SubscriptionActionRequest r) {
        var current=locked(id,r.version());
        String target=TRANSITIONS.getOrDefault(action,Map.of()).get(current.status());
        if(target==null) invalid("当前审批状态不允许此操作");
        if("confirm".equals(action)&&"REQUIRED".equals(current.providerReviewMode())&&current.providerConfirmedAt()==null)invalid("请先由所属提供方确认共享内容，再进行管理员审批");
        if(Set.of("submit","confirm").contains(action)) {
            checkBinding(current,"confirm".equals(action));validateRange(current.validFrom(),current.validUntil());
        }
        if("confirm".equals(action)&&!Boolean.TRUE.equals(r.wholeTopicAcknowledged())) invalid("确认时必须再次明确接受完整消息及历史范围");
        var now=now();Instant confirmed="confirm".equals(action)?now:"return".equals(action)?null:current.confirmedAt();
        Instant providerConfirmed=Set.of("submit","return").contains(action)?null:current.providerConfirmedAt();
        db.update("UPDATE kafka_subscription_grant SET status=?,confirmed_at=?,provider_confirmed_at=?,version=version+1,updated_at=? WHERE grant_id=? AND version=?",target,confirmed==null?null:sql(confirmed),providerConfirmed==null?null:sql(providerConfirmed),sql(now),id,r.version());
        if("revoke".equals(action))db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED',execution_state='REVOKE_PENDING',updated_at=? WHERE grant_id=? AND execution_state<>'REVOKED'",sql(now),id);
        event(id,action.toUpperCase(Locale.ROOT),current.status(),target,Boolean.TRUE.equals(r.wholeTopicAcknowledged()),r.reason(),r.version()+1,now);return get(id);
    }
    @Transactional
    public Grant providerReview(String id,String action,cn.railway.foundation.sharing.ProviderReviewRequest r){
        var current=locked(id,r.version());gate.providerReview(current.providerSubjectId(),current.resourceId());
        if(!"REQUIRED".equals(current.providerReviewMode())||!"PENDING".equals(current.status()))invalid("只有待审批且需要提供方确认的申请可操作");
        boolean confirm="provider-confirm".equals(action);
        if(!confirm&&!"provider-return".equals(action))invalid("提供方确认操作不合法");
        if(confirm){
            if(current.providerConfirmedAt()!=null)invalid("提供方已确认，请等待管理员审批");
            if(!Boolean.TRUE.equals(r.contentAcknowledged()))invalid("请明确核对用途、有效期和整主题完整消息及历史范围");
            checkBinding(current,true);validateRange(current.validFrom(),current.validUntil());
        }
        var now=now();String target=confirm?"PENDING":"DRAFT";
        db.update("UPDATE kafka_subscription_grant SET status=?,confirmed_at=NULL,provider_confirmed_at=?,version=version+1,updated_at=? WHERE grant_id=? AND version=?",target,confirm?sql(now):null,sql(now),id,r.version());
        event(id,confirm?"PROVIDER_CONFIRM":"PROVIDER_RETURN",current.status(),target,confirm,r.reason(),r.version()+1,now);
        return get(id);
    }
    Grant locked(String id,int version) {
        var rows=db.query("SELECT version FROM kafka_subscription_grant WHERE grant_id=? FOR UPDATE",(r,n)->r.getInt(1),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"订阅授权不存在");
        if(rows.get(0)!=version) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"订阅申请已变化，请刷新后重新操作");
        return get(id);
    }
    void requireEligible(Grant grant) {
        if(!"APPROVED".equals(grant.status())||grant.confirmedAt()==null||now().isBefore(grant.validFrom())||!now().isBefore(grant.validUntil()))invalid("申请须已明确确认且在有效期内");
        if("REQUIRED".equals(grant.providerReviewMode())&&grant.providerConfirmedAt()==null)invalid("所属提供方尚未确认共享内容");
        checkBinding(grant,true);
    }
    void executionEvent(Grant grant,String action,String reason) {
        var now=now();
        db.update("UPDATE kafka_subscription_grant SET version=version+1,updated_at=? WHERE grant_id=?",sql(now),grant.grantId());
        event(grant.grantId(),action,grant.status(),grant.status(),false,reason,grant.version()+1,now);
    }
    private void checkBinding(Grant grant,boolean confirming) {
        var binding=lockBinding(grant.providerSubjectId(),grant.demandSubjectId(),grant.resourceId(),grant.capabilityId());
        if(!grant.topic().equals(binding.topic())||!grant.authType().equals(binding.authType())) invalid("能力主题或认证方式已变化，请撤回并重新申请");
        if(confirming&&(!"ENABLED".equals(binding.status())||!"UP".equals(binding.health()))) invalid("请先验证并启用Kafka能力，再确认订阅申请");
    }
    private Binding lockBinding(String provider,String demand,String resource,String capability) {
        if(provider.equals(demand)) invalid("提供方与需求方必须是不同主体");
        for(String id:new TreeSet<>(List.of(provider,demand))) {
            var rows=db.query("SELECT subject_type,status FROM sharing_subject WHERE subject_id=? FOR UPDATE",(r,n)->List.of(r.getString(1),r.getString(2)),id);
            if(rows.isEmpty()||!rows.get(0).get(0).equals(id.equals(provider)?"PROVIDER":"DEMAND")||!"ENABLED".equals(rows.get(0).get(1))) invalid("共享主体不存在、角色不匹配或已停用");
        }
        var resources=db.query("SELECT status,source_id FROM data_resource WHERE resource_id=? FOR UPDATE",(r,n)->List.of(r.getString(1),r.getString(2)),resource);
        if(resources.isEmpty()||!"PUBLISHED".equals(resources.get(0).get(0))) invalid("请选择已发布资源");
        var sources=db.query("SELECT status,provider_subject_id FROM source_system WHERE source_id=? FOR UPDATE",(r,n)->new String[]{r.getString(1),r.getString(2)},resources.get(0).get(1));
        if(sources.isEmpty()||!"ENABLED".equals(sources.get(0)[0])) invalid("资源来源已停用");
        String owner=sources.get(0)[1];if(owner!=null&&!owner.equals(provider))Actor.deny();
        var caps=db.query("SELECT resource_id,type,status,health_status,topic,auth_type FROM capability WHERE capability_id=? FOR UPDATE",(r,n)->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),capability);
        if(caps.isEmpty()||!resource.equals(caps.get(0).get(0))||!"KAFKA_TOPIC".equals(caps.get(0).get(1))) invalid("请选择该资源下的Kafka主题能力；不能复用REST授权");
        var c=caps.get(0);
        if("RETIRED".equals(c.get(2))||!"SASL".equals(c.get(5))) invalid("能力已退役或未配置SASL独立身份认证");
        if(!c.get(4).matches("[a-zA-Z0-9._-]{1,249}")||Set.of(".","..").contains(c.get(4))) invalid("必须使用精确主题名称，不能使用通配符");
        return new Binding(c.get(4),c.get(5),c.get(2),c.get(3),owner);
    }
    private void event(String id,String action,String before,String after,boolean acknowledged,String reason,int version,Instant now) {
        db.update("INSERT INTO kafka_subscription_event(grant_id,action,from_status,to_status,whole_topic_acknowledged,operator_name,operator_user_id,reason,version,occurred_at,execution_state) VALUES(?,?,?,?,?,?,?,?,?,?,(SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=?))",id,action,before,after,acknowledged,Actor.operator(),Actor.userId(),reason.trim(),version,sql(now),id);
    }
    private RowMapper<Grant> mapper() {
        return (r,n)->{
            String state=r.getString("execution_state");if(state==null)state="NOT_PROVISIONED";
            boolean eligible="APPROVED".equals(r.getString("status"))&&time(r,"confirmed_at")!=null&&!now().isBefore(time(r,"valid_from"))&&now().isBefore(time(r,"valid_until"))
                    &&(!"REQUIRED".equals(r.getString("provider_review_mode"))||time(r,"provider_confirmed_at")!=null)
                    &&"ENABLED".equals(r.getString("provider_status"))&&"ENABLED".equals(r.getString("demand_status"))&&"PUBLISHED".equals(r.getString("resource_status"))
                    &&"ENABLED".equals(r.getString("source_status"))&&"ENABLED".equals(r.getString("capability_status"))&&"UP".equals(r.getString("health_status"))
                    &&r.getString("topic").equals(r.getString("current_topic"))&&r.getString("auth_type").equals(r.getString("current_auth"));
            boolean active="ACTIVE".equals(state)&&eligible&&broker.enabled();
            return new Grant(r.getString("grant_id"),r.getString("grant_name"),r.getString("provider_subject_id"),r.getString("provider_name"),r.getString("demand_subject_id"),r.getString("demand_name"),r.getString("resource_id"),r.getString("resource_name"),r.getString("capability_id"),r.getString("capability_name"),r.getString("topic"),r.getString("auth_type"),r.getString("consumer_group"),r.getString("scope"),r.getString("purpose"),time(r,"valid_from"),time(r,"valid_until"),r.getString("status"),r.getInt("version"),time(r,"confirmed_at"),time(r,"created_at"),time(r,"updated_at"),state,active,r.getString("broker_username"),r.getString("last_error"),time(r,"checked_at"),active&&r.getBoolean("has_secret")&&time(r,"issued_at")==null,broker.enabled(),r.getString("provider_review_mode"),time(r,"provider_confirmed_at"));
        };
    }
    private static Instant time(ResultSet r,String column) throws SQLException { var t=r.getTimestamp(column);return t==null?null:t.toLocalDateTime().toInstant(ZoneOffset.UTC); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static LocalDateTime sql(Instant value) {
        try {var t=LocalDateTime.ofInstant(value,ZoneOffset.UTC);if(t.getYear()<1000||t.getYear()>9999||value.getNano()%1000000!=0) invalid("时间超出数据库范围或精度超过毫秒");return t;}
        catch(DateTimeException e) {throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"时间范围不合法");}
    }
    private static void validateRange(Instant from,Instant until) {
        if(from==null||until==null||!from.isBefore(until)||!until.isAfter(now())) invalid("有效期结束必须晚于开始且尚未到期");sql(from);sql(until);
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message); }
}
