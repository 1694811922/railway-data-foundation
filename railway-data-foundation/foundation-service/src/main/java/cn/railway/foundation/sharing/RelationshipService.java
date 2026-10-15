package cn.railway.foundation.sharing;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Service
public class RelationshipService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Policy(String policyId, String resourceId, List<String> allowedFields, String timeField,
                         Instant dataFrom, Instant dataUntil, int version) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Relationship(String relationshipId, String relationshipName, String providerSubjectId,
                               String providerName, String demandSubjectId, String demandName, String resourceId,
                               String resourceName, String capabilityId, String capabilityName, String capabilityType,
                               String purpose, Instant validFrom, Instant validUntil, String status, int version,
                               Instant confirmedAt, Instant createdAt, Instant updatedAt, Policy policy,
                               String providerReviewMode, Instant providerConfirmedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(long eventId, String relationshipId, String action, String fromStatus, String toStatus,
                        String operatorName, String reason, int version, Instant occurredAt) {}
    private record Binding(String capabilityType, String capabilityStatus, String healthStatus,String owner) {}
    private record Field(String name, String type, boolean time) {}
    private static final String SELECT = "SELECT r.*,p.subject_name AS provider_name,d.subject_name AS demand_name,"+
            "res.resource_name,c.capability_name,c.type AS capability_type,a.allowed_fields,a.time_field,a.data_from,a.data_until,a.version AS policy_version FROM sharing_relationship r "+
            "JOIN sharing_subject p ON p.subject_id=r.provider_subject_id JOIN sharing_subject d ON d.subject_id=r.demand_subject_id "+
            "JOIN data_resource res ON res.resource_id=r.resource_id JOIN capability c ON c.capability_id=r.capability_id JOIN access_policy a ON a.policy_id=r.policy_id ";
    private static final Map<String,Map<String,String>> TRANSITIONS = Map.of(
            "submit",Map.of("DRAFT","PENDING"),
            "confirm",Map.of("PENDING","CONFIRMED"),
            "return",Map.of("PENDING","DRAFT"),
            "enable",Map.of("CONFIRMED","ACTIVE","SUSPENDED","ACTIVE"),
            "suspend",Map.of("ACTIVE","SUSPENDED"),
            "revoke",Map.of("DRAFT","REVOKED","PENDING","REVOKED","CONFIRMED","REVOKED","ACTIVE","REVOKED","SUSPENDED","REVOKED"));
    private final JdbcTemplate db;
    private final WorkspaceGate gate;
    private final ObjectMapper json;
    public RelationshipService(JdbcTemplate db, ObjectMapper json,WorkspaceGate gate) { this.db=db; this.gate=gate; this.json=json; }

    public List<Relationship> list(int limit, int offset) {
        return db.query(SELECT+"WHERE "+Actor.sharing("r")+" ORDER BY r.updated_at DESC,r.relationship_id DESC LIMIT ? OFFSET ?",mapper(),Actor.args(limit,offset));
    }
    public Relationship get(String id) {
        var rows=db.query(SELECT+"WHERE r.relationship_id=?",mapper(),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"共享关系不存在");
        return rows.get(0);
    }
    public Policy policy(String id) {
        var rows=db.query("SELECT * FROM access_policy WHERE policy_id=?",(r,n)->policyRow(r),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"访问策略不存在");
        return rows.get(0);
    }
    public List<Policy> policies(int limit,int offset) {
        return db.query("SELECT a.* FROM access_policy a WHERE EXISTS(SELECT 1 FROM sharing_relationship r WHERE r.policy_id=a.policy_id AND "+Actor.sharing("r")+") ORDER BY a.updated_at DESC,a.policy_id DESC LIMIT ? OFFSET ?",(r,n)->policyRow(r),Actor.args(limit,offset));
    }
    public List<Event> events(String id) {
        get(id);
        return db.query("SELECT * FROM sharing_relationship_event WHERE relationship_id=? ORDER BY version",
                (r,n)->new Event(r.getLong("event_id"),r.getString("relationship_id"),r.getString("action"),r.getString("from_status"),
                        r.getString("to_status"),r.getString("operator_name"),r.getString("reason"),r.getInt("version"),time(r,"occurred_at")),id);
    }
    @Transactional
    public Relationship create(RelationshipRequest r) {
        gate.newApplication(r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId());
        var binding=lockBinding(r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId());
        validateRange(r.validFrom(),r.validUntil(),true);
        validatePolicy(r.resourceId(),r.policy());
        String id=id("REL"),policyId=id("POL");var now=now();
        db.update("INSERT INTO access_policy(policy_id,resource_id,allowed_fields,time_field,data_from,data_until,version,created_at,updated_at) VALUES(?,?,?,?,?,?,1,?,?)",
                policyId,r.resourceId(),fieldsJson(r.policy().allowedFields()),r.policy().timeField(),sql(r.policy().dataFrom()),sql(r.policy().dataUntil()),sql(now),sql(now));
        db.update("INSERT INTO sharing_relationship(relationship_id,relationship_name,provider_subject_id,demand_subject_id,resource_id,capability_id,policy_id,purpose,valid_from,valid_until,status,version,created_at,updated_at,provider_review_mode) VALUES(?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?,?,?)",
                id,r.relationshipName().trim(),r.providerSubjectId(),r.demandSubjectId(),r.resourceId(),r.capabilityId(),policyId,r.purpose().trim(),sql(r.validFrom()),sql(r.validUntil()),sql(now),sql(now),binding.owner()==null?"ADMIN_MANAGED":"REQUIRED");
        event(id,"CREATE",null,"DRAFT",r.reason(),1,now);
        return get(id);
    }
    @Transactional
    public Relationship update(String id,RelationshipUpdateRequest r) {
        var current=locked(id,r.version());
        if(!"DRAFT".equals(current.status())) invalid("仅草稿关系可编辑；确认后需撤回并重新登记，避免绕过审核");
        lockBinding(current.providerSubjectId(),current.demandSubjectId(),current.resourceId(),current.capabilityId());
        validateRange(r.validFrom(),r.validUntil(),true);validatePolicy(current.resourceId(),r.policy());var now=now();
        db.update("UPDATE access_policy SET allowed_fields=?,time_field=?,data_from=?,data_until=?,version=version+1,updated_at=? WHERE policy_id=?",
                fieldsJson(r.policy().allowedFields()),r.policy().timeField(),sql(r.policy().dataFrom()),sql(r.policy().dataUntil()),sql(now),current.policy().policyId());
        db.update("UPDATE sharing_relationship SET relationship_name=?,purpose=?,valid_from=?,valid_until=?,confirmed_at=NULL,provider_confirmed_at=NULL,version=version+1,updated_at=? WHERE relationship_id=? AND version=?",
                r.relationshipName().trim(),r.purpose().trim(),sql(r.validFrom()),sql(r.validUntil()),sql(now),id,r.version());
        event(id,"UPDATE",current.status(),current.status(),r.reason(),r.version()+1,now);
        return get(id);
    }
    @Transactional
    public Relationship action(String id,String action,RelationshipActionRequest r) {
        var current=locked(id,r.version());
        var target=TRANSITIONS.getOrDefault(action,Map.of()).get(current.status());
        if(target==null) invalid("当前状态不允许此操作，请刷新并按确认流程处理");
        if(Set.of("confirm","enable").contains(action)&&"REQUIRED".equals(current.providerReviewMode())&&current.providerConfirmedAt()==null)invalid("请先由所属提供方确认共享内容，再进行管理员审批");
        var now=now();Instant confirmation=current.confirmedAt();
        if(Set.of("submit","confirm","enable").contains(action)) {
            var binding=lockBinding(current.providerSubjectId(),current.demandSubjectId(),current.resourceId(),current.capabilityId());
            validateRange(current.validFrom(),current.validUntil(),true);
            var p=current.policy();validatePolicy(current.resourceId(),new PolicyRequest(p.allowedFields(),p.timeField(),p.dataFrom(),p.dataUntil()));
            if("enable".equals(action)) {
                if(confirmation==null) invalid("共享关系尚未确认");
                if(now.isBefore(current.validFrom())) invalid("尚未到授权有效期，请在生效时间后启用");
                if(!"REST_API".equals(binding.capabilityType())) invalid("该能力类型尚未接入权限执行，暂不可启用关系");
                if(!"ENABLED".equals(binding.capabilityStatus()) || !"UP".equals(binding.healthStatus())) invalid("请先验证并启用所属接口能力");
            }
        }
        if("confirm".equals(action)) confirmation=now;
        if("return".equals(action)) confirmation=null;
        Instant providerConfirmed=Set.of("submit","return").contains(action)?null:current.providerConfirmedAt();
        db.update("UPDATE sharing_relationship SET status=?,confirmed_at=?,provider_confirmed_at=?,version=version+1,updated_at=? WHERE relationship_id=? AND version=?",
                target,confirmation==null?null:sql(confirmation),providerConfirmed==null?null:sql(providerConfirmed),sql(now),id,r.version());
        event(id,action.toUpperCase(Locale.ROOT),current.status(),target,r.reason(),r.version()+1,now);
        return get(id);
    }
    @Transactional
    public Relationship providerReview(String id,String action,ProviderReviewRequest r){
        var current=locked(id,r.version());gate.providerReview(current.providerSubjectId(),current.resourceId());
        if(!"REQUIRED".equals(current.providerReviewMode())||!"PENDING".equals(current.status()))invalid("只有待审批且需要提供方确认的申请可操作");
        boolean confirm="provider-confirm".equals(action);
        if(!confirm&&!"provider-return".equals(action))invalid("提供方确认操作不合法");
        if(confirm){
            if(current.providerConfirmedAt()!=null)invalid("提供方已确认，请等待管理员审批");
            if(!Boolean.TRUE.equals(r.contentAcknowledged()))invalid("请明确核对用途、有效期、允许字段和数据时间范围");
            lockBinding(current.providerSubjectId(),current.demandSubjectId(),current.resourceId(),current.capabilityId());
            validateRange(current.validFrom(),current.validUntil(),true);
            var policy=current.policy();validatePolicy(current.resourceId(),new PolicyRequest(policy.allowedFields(),policy.timeField(),policy.dataFrom(),policy.dataUntil()));
        }
        var now=now();String target=confirm?"PENDING":"DRAFT";
        db.update("UPDATE sharing_relationship SET status=?,confirmed_at=NULL,provider_confirmed_at=?,version=version+1,updated_at=? WHERE relationship_id=? AND version=?",target,confirm?sql(now):null,sql(now),id,r.version());
        event(id,confirm?"PROVIDER_CONFIRM":"PROVIDER_RETURN",current.status(),target,r.reason(),r.version()+1,now);
        return get(id);
    }
    private Relationship locked(String id,int version) {
        var versions=db.query("SELECT version FROM sharing_relationship WHERE relationship_id=? FOR UPDATE",(r,n)->r.getInt(1),id);
        if(versions.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"共享关系不存在");
        if(versions.get(0)!=version) throw new BusinessException(ErrorCode.VERSION_CONFLICT,"共享关系已变化，请刷新后重新操作");
        return get(id);
    }
    private Binding lockBinding(String provider,String demand,String resource,String capability) {
        if(provider.equals(demand)) invalid("提供方与需求方必须是不同主体");
        // Stable lock order prevents two opposite party selections from deadlocking.
        for(String id:new TreeSet<>(List.of(provider,demand))) {
            var subjects=db.query("SELECT subject_type,status FROM sharing_subject WHERE subject_id=? FOR UPDATE",
                    (r,n)->List.of(r.getString(1),r.getString(2)),id);
            if(subjects.isEmpty()) invalid("共享主体不存在，请刷新主体目录");
            var subject=subjects.get(0);
            if(!subject.get(0).equals(id.equals(provider)?"PROVIDER":"DEMAND")) invalid("提供方或需求方主体角色不匹配");
            if(!"ENABLED".equals(subject.get(1))) invalid("共享主体已停用，请先处理主体状态");
        }
        var resources=db.query("SELECT status,source_id FROM data_resource WHERE resource_id=? FOR UPDATE",(r,n)->List.of(r.getString(1),r.getString(2)),resource);
        if(resources.isEmpty() || !"PUBLISHED".equals(resources.get(0).get(0))) invalid("请选择已发布的数据资源");
        var sources=db.query("SELECT status,provider_subject_id FROM source_system WHERE source_id=? FOR UPDATE",(r,n)->new String[]{r.getString(1),r.getString(2)},resources.get(0).get(1));
        if(sources.isEmpty() || !"ENABLED".equals(sources.get(0)[0])) invalid("资源来源已停用");
        String owner=sources.get(0)[1];if(owner!=null&&!owner.equals(provider))Actor.deny();
        var capabilities=db.query("SELECT resource_id,type,status,health_status FROM capability WHERE capability_id=? FOR UPDATE",
                (r,n)->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),capability);
        if(capabilities.isEmpty() || !resource.equals(capabilities.get(0).get(0))) invalid("接口能力不属于所选资源");
        var c=capabilities.get(0);
        if("RETIRED".equals(c.get(2))) invalid("接口能力已退役");
        return new Binding(c.get(1),c.get(2),c.get(3),owner);
    }
    private void validatePolicy(String resource,PolicyRequest p) {
        validateRange(p.dataFrom(),p.dataUntil(),false);
        var fields=db.query("SELECT field_name,data_type,time_field FROM resource_field WHERE resource_id=? ORDER BY field_name FOR UPDATE",
                (r,n)->new Field(r.getString(1),r.getString(2),r.getBoolean(3)),resource);
        var names=new HashSet<String>();for(var field:fields)names.add(field.name());
        if(p.allowedFields().isEmpty() || p.allowedFields().size()>200 || new HashSet<>(p.allowedFields()).size()!=p.allowedFields().size()) invalid("允许字段不能为空或重复，最多200项");
        if(p.allowedFields().contains("*") || !names.containsAll(p.allowedFields())) invalid("允许字段必须来自所选资源，不能使用通配符或其他资源字段");
        var time=fields.stream().filter(f->f.name().equals(p.timeField())).findFirst().orElse(null);
        if(time==null || !time.time() || !time.type().toLowerCase(Locale.ROOT).matches("(datetime|timestamp|date)(\\(.*\\))?"))
            invalid("时间条件必须选择该资源中人工标记的日期或时间字段");
    }
    private static void validateRange(Instant from,Instant until,boolean futureEnd) {
        if(from==null || until==null || !from.isBefore(until)) invalid("起止时间不合法，结束时间必须晚于开始时间");
        sql(from);sql(until);
        if(futureEnd && !until.isAfter(now())) invalid("授权有效期已结束，请调整有效期");
    }
    private void event(String id,String action,String before,String after,String reason,int version,Instant now) {
        db.update("INSERT INTO sharing_relationship_event(relationship_id,action,from_status,to_status,operator_name,operator_user_id,reason,version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,action,before,after,Actor.operator(),Actor.userId(),reason.trim(),version,sql(now));
    }
    private RowMapper<Relationship> mapper() {
        return (r,n)->new Relationship(r.getString("relationship_id"),r.getString("relationship_name"),r.getString("provider_subject_id"),r.getString("provider_name"),
                r.getString("demand_subject_id"),r.getString("demand_name"),r.getString("resource_id"),r.getString("resource_name"),r.getString("capability_id"),
                r.getString("capability_name"),r.getString("capability_type"),r.getString("purpose"),time(r,"valid_from"),time(r,"valid_until"),r.getString("status"),
                r.getInt("version"),time(r,"confirmed_at"),time(r,"created_at"),time(r,"updated_at"),policyRow(r,"policy_version"),r.getString("provider_review_mode"),time(r,"provider_confirmed_at"));
    }
    private Policy policyRow(ResultSet r) throws SQLException {
        return policyRow(r,"version");
    }
    private Policy policyRow(ResultSet r,String versionColumn) throws SQLException {
        try {
            List<String> fields=json.readValue(r.getString("allowed_fields"),new TypeReference<>(){});
            return new Policy(r.getString("policy_id"),r.getString("resource_id"),List.copyOf(fields),r.getString("time_field"),time(r,"data_from"),time(r,"data_until"),r.getInt(versionColumn));
        } catch(Exception e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"访问策略读取失败，请检查策略记录"); }
    }
    private String fieldsJson(List<String> fields) {
        try { return json.writeValueAsString(fields); } catch(Exception e) {throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"字段条件格式不合法");}
    }
    private static Instant time(ResultSet r,String column) throws SQLException {
        var t=r.getTimestamp(column);return t==null?null:t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime sql(Instant value) {
        try {
            var t=LocalDateTime.ofInstant(value,ZoneOffset.UTC);
            if(t.getYear()<1000 || t.getYear()>9999 || value.getNano()%1000000!=0) invalid("时间必须在数据库支持范围内，精度最多毫秒");
            return t;
        } catch(DateTimeException e) {throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"时间格式或范围不合法");}
    }
    private static Instant now() {return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private static String id(String prefix) {return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    private static void invalid(String message) {throw new BusinessException(ErrorCode.BUSINESS_ERROR,message);}
}
