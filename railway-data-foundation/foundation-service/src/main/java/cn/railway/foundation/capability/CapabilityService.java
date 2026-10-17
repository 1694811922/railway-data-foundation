package cn.railway.foundation.capability;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CapabilityService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Capability(String capabilityId,String capabilityName,String resourceId,String resourceName,String type,
            String httpMethod,String endpoint,String topic,String sourceTable,String protocol,String authType,
            String messageSchema,String queryTemplateCode,String description,String status,String healthStatus,
            int version,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RestRuntime(String capabilityId, String status, boolean enabled, String reason,
            String sourceHost, Integer sourcePort, String databaseName, String physicalTable) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RestInvocation(String capabilityId, String httpMethod, String providerBaseUrl, String endpoint,
            String requestUrl, String headerName, String headerValue, boolean enabled, String reason,
            List<String> requiredHeaders, String authorizationNote) {}
    private record Attributes(String httpMethod,String endpoint,String topic,String sourceTable,String protocol,
            String authType,String messageSchema,String queryTemplateCode) {}
    private final JdbcTemplate db;
    private final WorkspaceGate gate;
    private final RestCapabilityVerifier restVerifier;
    private final KafkaCapabilityVerifier kafkaVerifier;
    private final ProviderSourceBinding binding;
    public CapabilityService(JdbcTemplate db,RestCapabilityVerifier restVerifier,KafkaCapabilityVerifier kafkaVerifier,ProviderSourceBinding binding,WorkspaceGate gate) {
        this.db=db; this.gate=gate; this.restVerifier=restVerifier; this.kafkaVerifier=kafkaVerifier; this.binding=binding;
    }

    public List<Capability> list(int limit,int offset) {
        return db.query("SELECT c.*,r.resource_name FROM capability c JOIN data_resource r ON r.resource_id=c.resource_id WHERE "+Actor.capability("c","r")+" ORDER BY c.updated_at DESC,c.capability_id DESC LIMIT ? OFFSET ?",
                (rs,n)->row(rs),Actor.args(limit,offset));
    }
    public List<Capability> byResource(String resourceId) {
        requireResource(resourceId);
        return db.query("SELECT c.*,r.resource_name FROM capability c JOIN data_resource r ON r.resource_id=c.resource_id WHERE "+Actor.capability("c","r")+" AND c.resource_id=? ORDER BY c.created_at,c.capability_id",
                (rs,n)->row(rs),Actor.args(resourceId));
    }
    public Capability get(String id) {
        var found=db.query("SELECT c.*,r.resource_name FROM capability c JOIN data_resource r ON r.resource_id=c.resource_id WHERE c.capability_id=?",
                (rs,n)->row(rs),id);
        if (found.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"接口能力不存在");
        return found.get(0);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Capability create(CapabilityRequest request) {
        gate.resource(request.resourceId());
        cn.railway.foundation.resource.ResourceLocks.lock(db,request.resourceId());
        var resource=requireResource(request.resourceId());
        if (!"PUBLISHED".equals(resource.status())) throw new BusinessException(ErrorCode.BUSINESS_ERROR,"请先发布所属资源");
        Attributes a=validate(request,resource.physicalTable());
        String id="CAP_"+UUID.randomUUID().toString().replace("-","");
        LocalDateTime now=now();
        try {
            db.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,http_method,endpoint,topic,source_table,protocol,auth_type,message_schema,query_template_code,description,status,health_status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT','UNKNOWN',1,?,?)",
                    id,request.resourceId(),request.capabilityName().strip(),request.type(),a.httpMethod(),a.endpoint(),a.topic(),
                    a.sourceTable(),a.protocol(),a.authType(),a.messageSchema(),a.queryTemplateCode(),clean(request.description()),now,now);
        } catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.BUSINESS_ERROR,"同一资源下能力名称不能重复"); }
        return get(id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Capability update(String id,CapabilityRequest request) {
        lockMutable(id,false);
        db.query("SELECT capability_id FROM capability WHERE capability_id=? FOR UPDATE",(r,n)->r.getString(1),id);
        Capability current=get(id);
        if (!"DRAFT".equals(current.status())) throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅草稿能力可以编辑");
        if (!current.resourceId().equals(request.resourceId()) || !current.type().equals(request.type()))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"所属资源和能力类型不可更改，请重新登记");
        Attributes a=validate(request,requireResource(current.resourceId()).physicalTable());
        if ((!current.topic().equals(a.topic()) || !current.authType().equals(a.authType())) &&
                db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_grant WHERE capability_id=?",Integer.class,id)>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"主题或认证方式已被订阅审批引用，请登记新的能力");
        try {
            int changed=db.update("UPDATE capability SET capability_name=?,http_method=?,endpoint=?,topic=?,source_table=?,protocol=?,auth_type=?,message_schema=?,query_template_code=?,description=?,version=version+1,updated_at=? WHERE capability_id=? AND version=? AND status='DRAFT'",
                    request.capabilityName().strip(),a.httpMethod(),a.endpoint(),a.topic(),a.sourceTable(),a.protocol(),a.authType(),
                    a.messageSchema(),a.queryTemplateCode(),clean(request.description()),now(),id,current.version());
            if (changed==0) stateChanged();
        } catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.BUSINESS_ERROR,"同一资源下能力名称不能重复"); }
        return get(id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Capability verify(String id) {
        lockMutable(id,true);
        Capability current=get(id);
        if (!Set.of("DRAFT","VERIFIED","DISABLED").contains(current.status()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"当前状态不允许重新验证");
        boolean up=switch (current.type()) {
            case "REST_API" -> restVerifier.probe(current);
            case "KAFKA_TOPIC" -> kafkaVerifier.probe(current);
            default -> throw new BusinessException(ErrorCode.BUSINESS_ERROR,"当前能力类型尚未接入验证");
        };
        String status="DISABLED".equals(current.status())?"DISABLED":up?"VERIFIED":"DRAFT";
        int changed=db.update("UPDATE capability SET status=?,health_status=?,version=version+1,updated_at=? WHERE capability_id=? AND version=? AND status IN ('DRAFT','VERIFIED','DISABLED')",
                status,up?"UP":"DOWN",now(),id,current.version());
        if (changed==0) stateChanged();
        return get(id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=BusinessException.class)
    public Capability enable(String id) {
        lockMutable(id,true);
        Capability current=get(id);
        requireRuntime(current);
        if ("ENABLED".equals(current.status())) return current;
        if (!Set.of("VERIFIED","DISABLED").contains(current.status()) || !"UP".equals(current.healthStatus()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力须经过真实验证且健康状态为UP后才能启用");
        // Probe again before enabling: an old UP result is insufficient.
        if (!("KAFKA_TOPIC".equals(current.type())?kafkaVerifier.probe(current):restVerifier.probe(current))) {
            int changed=db.update("UPDATE capability SET health_status='DOWN',version=version+1,updated_at=? WHERE capability_id=? AND version=? AND status=?",
                    now(),id,current.version(),current.status());
            if (changed==0) stateChanged();
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"启用前连接检查失败，请恢复服务后重新验证");
        }
        int changed=db.update("UPDATE capability SET status='ENABLED',version=version+1,updated_at=? WHERE capability_id=? AND version=? AND status=? AND health_status='UP'",
                now(),id,current.version(),current.status());
        if (changed==0) stateChanged();
        return get(id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Capability disable(String id) {
        lockMutable(id,false);
        Capability current=get(id);
        // Disabling must remain possible when Broker configuration or Source is unavailable.
        if(!supportedRest(current) && !supportedKafka(current)) requireRestRuntime(current);
        if ("DISABLED".equals(current.status())) return current;
        if (!"ENABLED".equals(current.status()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅已启用的能力可以停用");
        int changed=db.update("UPDATE capability SET status='DISABLED',version=version+1,updated_at=? WHERE capability_id=? AND version=? AND status='ENABLED'",
                now(),id,current.version());
        if (changed==0) stateChanged();
        return get(id);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(String id) {
        lockMutable(id,false);
        db.query("SELECT capability_id FROM capability WHERE capability_id=? FOR UPDATE",(r,n)->r.getString(1),id);
        Capability current=get(id);
        if (!"DRAFT".equals(current.status()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅草稿能力可以删除；已验证或已使用能力应保留历史");
        Integer references=db.queryForObject("SELECT COUNT(*) FROM sharing_relationship WHERE capability_id=?",Integer.class,id);
        if (references!=null && references>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力已被共享关系引用，不能删除；请保留登记与历史");
        if (db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_grant WHERE capability_id=?",Integer.class,id)>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力已被Kafka订阅审批引用，不能删除；请保留登记与历史");
        if (db.update("DELETE FROM capability WHERE capability_id=? AND status='DRAFT' AND version=?",id,current.version())!=1)
            stateChanged();
    }

    /** Provider fetches only control metadata; no passwords, SQL or business rows cross this API. */
    public RestRuntime runtime(String id) {
        Capability current=get(id);
        if (!supportedRest(current)) return denied(current,"该能力尚未接入运行时控制");
        if (!"ENABLED".equals(current.status())) return denied(current,"接口能力尚未启用或已停用");
        if (!"UP".equals(current.healthStatus())) return denied(current,"接口能力健康状态异常");
        try {
            var target=binding.requireSupported(current.resourceId());
            return new RestRuntime(id,current.status(),true,"接口能力可用",target.host(),target.port(),target.databaseName(),target.physicalTable());
        } catch (BusinessException ex) { return denied(current,ex.getMessage()); }
    }
    public record KafkaRuntime(@JsonProperty("capability_id") String capabilityId,String type,String status,
            boolean enabled,String reason,String topic,@JsonProperty("auth_type") String authType,
            @JsonProperty("resource_id") String resourceId,
            @JsonProperty("source_host") String sourceHost,@JsonProperty("source_port") Integer sourcePort,
            @JsonProperty("database_name") String databaseName,@JsonProperty("physical_table") String physicalTable) {}
    /** Production admission only. This does not create subscriber ACLs or grant whole-topic access. */
    public KafkaRuntime kafkaRuntime(String id) {
        var current=get(id);
        String reason="消息能力未启用、健康状态异常或类型不支持";
        if(supportedKafka(current)&&"ENABLED".equals(current.status())&&"UP".equals(current.healthStatus())) {
            try {
                kafkaVerifier.requireConfigured(current);
                var target=binding.requireSupported(current.resourceId());
                return new KafkaRuntime(id,current.type(),current.status(),true,"允许提供服务发送新消息",current.topic(),current.authType(),current.resourceId(),
                        target.host(),target.port(),target.databaseName(),target.physicalTable());
            } catch(BusinessException ex){reason=ex.getMessage();}
        }
        return new KafkaRuntime(id,current.type(),current.status(),false,reason,current.topic(),current.authType(),current.resourceId(),null,null,null,null);
    }
    private void requireRuntime(Capability current) {
        if(supportedKafka(current)) kafkaVerifier.requireConfigured(current);
        else requireRestRuntime(current);
    }
    private static boolean supportedKafka(Capability current) {
        return "KAFKA_TOPIC".equals(current.type())&&"KAFKA".equals(current.protocol())&&Set.of("NONE","SASL").contains(current.authType());
    }
    /** Read-only calling instructions. Does not probe, query business data, or change capability state. */
    public RestInvocation invocation(String id) {
        Capability current=get(id);
        requireRestRuntime(current);
        var base=restVerifier.providerBase(current);
        var state=runtime(id);
        return new RestInvocation(id,current.httpMethod(),base.toString(),current.endpoint(),
                base.resolve(current.endpoint()).toString(),"X-Capability-Id",id,state.enabled(),state.reason(),
                List.of("Authorization","X-Relationship-Id","X-Capability-Id"),"能力可用不代表访问已授权；还需有效需求方调用令牌及已确认启用的共享关系");
    }
    private static RestRuntime denied(Capability current,String reason) {
        return new RestRuntime(current.capabilityId(),current.status(),false,reason,null,null,null,null);
    }
    private static boolean supportedRest(Capability current) {
        return "REST_API".equals(current.type()) && "GET".equals(current.httpMethod())
                && "NONE".equals(current.authType()) && "/containers/events".equals(current.endpoint());
    }
    private static void requireRestRuntime(Capability current) {
        if (!supportedRest(current))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"当前仅支持集装箱事件REST查询能力的运行时启停；其他类型暂未接入");
    }
    private static void stateChanged() {
        throw new BusinessException(ErrorCode.BUSINESS_ERROR,"能力状态已变化，请刷新后重试");
    }

    private void lockMutable(String id,boolean published) {
        String resource=cn.railway.foundation.resource.ResourceLocks.child(db,"capability",id);
        String state=cn.railway.foundation.resource.ResourceLocks.lock(db,resource);
        if("RETIRED".equals(state)||published&&!"PUBLISHED".equals(state))throw new BusinessException(ErrorCode.BUSINESS_ERROR,"资源已下架或退役，不能执行该能力操作");
    }
    private record ResourceRef(String status,String physicalTable) {}
    private ResourceRef requireResource(String id) {
        var rows=db.query("SELECT status,physical_table FROM data_resource WHERE resource_id=?",
                (rs,n)->new ResourceRef(rs.getString(1),rs.getString(2)),id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"所属资源不存在");
        return rows.get(0);
    }
    private Attributes validate(CapabilityRequest r,String physicalTable) {
        String type=r.type(),method=clean(r.httpMethod()),endpoint=clean(r.endpoint()),topic=clean(r.topic());
        String sourceTable=clean(r.sourceTable()),protocol=clean(r.protocol()),auth=clean(r.authType());
        String schema=clean(r.messageSchema()),query=clean(r.queryTemplateCode());
        if (!Set.of("REST_API","KAFKA_TOPIC","CDC_STREAM","READONLY_QUERY").contains(type)) invalid("不支持的能力类型");
        switch (type) {
            case "REST_API" -> {
                if (!Set.of("GET","POST").contains(method) || !endpoint.matches("/(?!/)[A-Za-z0-9/_{}.-]{1,499}")
                        || !Set.of("HTTP","HTTPS").contains(protocol) || !Set.of("NONE","TOKEN","BASIC").contains(auth))
                    invalid("REST能力的请求方式、相对路径、协议或认证类型不合法");
                return new Attributes(method,endpoint,"","",protocol,auth,"","");
            }
            case "KAFKA_TOPIC" -> {
                if (!validTopic(topic) || !"KAFKA".equals(protocol) || !Set.of("NONE","SASL").contains(auth))
                    invalid("Kafka能力的Topic、协议或认证类型不合法");
                return new Attributes("","",topic,"",protocol,auth,schema,"");
            }
            case "CDC_STREAM" -> {
                if (!validTopic(topic) || !physicalTable.equals(sourceTable) || !"DEBEZIUM".equals(protocol))
                    invalid("CDC能力必须引用所属资源的物理表，并填写有效Topic与DEBEZIUM协议");
                return new Attributes("","",topic,sourceTable,protocol,"","","");
            }
            default -> {
                if (!query.matches("[A-Za-z][A-Za-z0-9_]{0,119}")) invalid("只读查询仅登记模板代码，不接收SQL文本");
                return new Attributes("","","","","","","",query);
            }
        }
    }
    private static boolean validTopic(String topic) { return topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}"); }
    private static String clean(String value) { return value==null?"":value.strip(); }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static Capability row(ResultSet rs) throws SQLException {
        return new Capability(rs.getString("capability_id"),rs.getString("capability_name"),rs.getString("resource_id"),
                rs.getString("resource_name"),rs.getString("type"),rs.getString("http_method"),rs.getString("endpoint"),
                rs.getString("topic"),rs.getString("source_table"),rs.getString("protocol"),rs.getString("auth_type"),
                rs.getString("message_schema"),rs.getString("query_template_code"),rs.getString("description"),
                rs.getString("status"),rs.getString("health_status"),rs.getInt("version"),
                rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
