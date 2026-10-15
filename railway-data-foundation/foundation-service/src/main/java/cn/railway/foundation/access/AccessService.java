package cn.railway.foundation.access;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.capability.CapabilityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Decisions contain governance metadata only; business rows never enter Foundation. */
@Service
public class AccessService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Decision(boolean allowed,String reasonCode,String reason,String relationshipId,String capabilityId,
                           String subjectId,String resourceId,String policyId,Integer relationshipVersion,Integer policyVersion,
                           List<String> allowedFields,List<String> filterFields,String timeField,Instant dataFrom,Instant dataUntil,
                           String sourceHost,Integer sourcePort,String databaseName,String physicalTable,
                           Instant checkedAt,Instant validUntil) {}
    private record Scope(String demand,String providerStatus,String demandStatus,String resource,String policy,String status,
                         Instant validFrom,Instant validUntil,Instant confirmed,int version,List<String> fields,String timeField,
                         Instant dataFrom,Instant dataUntil,int policyVersion,String providerReviewMode,Instant providerConfirmedAt) {}
    private final JdbcTemplate db;
    private final CallerCredentialService credentials;
    private final CapabilityService capabilities;
    private final ObjectMapper json;
    public AccessService(JdbcTemplate db,CallerCredentialService credentials,CapabilityService capabilities,ObjectMapper json){this.db=db;this.credentials=credentials;this.capabilities=capabilities;this.json=json;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Decision check(String authorization,AccessRequest request){
        Instant now=CallerCredentialService.now();
        var identity=credentials.authenticate(authorization,now);
        if(identity==null)return deny(request,"IDENTITY_INVALID","调用凭据无效、到期、撤销或所属需求方不可用");
        var rows=db.query("SELECT x.*,p.subject_name,p.status AS provider_status,d.status AS demand_status,a.allowed_fields,a.time_field,a.data_from,a.data_until,a.version AS policy_version FROM sharing_relationship x JOIN sharing_subject p ON p.subject_id=x.provider_subject_id JOIN sharing_subject d ON d.subject_id=x.demand_subject_id JOIN access_policy a ON a.policy_id=x.policy_id AND a.resource_id=x.resource_id WHERE x.relationship_id=? AND x.capability_id=?",
                (r,n)->new Scope(r.getString("demand_subject_id"),r.getString("provider_status"),r.getString("demand_status"),r.getString("resource_id"),r.getString("policy_id"),r.getString("status"),
                        CallerCredentialService.time(r,"valid_from"),CallerCredentialService.time(r,"valid_until"),CallerCredentialService.time(r,"confirmed_at"),r.getInt("version"),
                        readFields(r.getString("allowed_fields")),r.getString("time_field"),CallerCredentialService.time(r,"data_from"),CallerCredentialService.time(r,"data_until"),r.getInt("policy_version"),r.getString("provider_review_mode"),CallerCredentialService.time(r,"provider_confirmed_at")),request.relationshipId(),request.capabilityId());
        if(rows.isEmpty()||!identity.subjectId().equals(rows.get(0).demand()))return deny(request,"RELATIONSHIP_UNAVAILABLE","共享关系或能力与调用方不匹配",identity.subjectId(),null);
        var scope=rows.get(0);
        if(!"ENABLED".equals(scope.providerStatus())||!"ENABLED".equals(scope.demandStatus()))return deny(request,"SUBJECT_UNAVAILABLE","提供方或需求方已停用",identity.subjectId(),scope);
        if(!"ACTIVE".equals(scope.status())||scope.confirmed()==null||"REQUIRED".equals(scope.providerReviewMode())&&scope.providerConfirmedAt()==null)return deny(request,"RELATIONSHIP_INACTIVE","共享关系未完成确认启用、已暂停或已撤回",identity.subjectId(),scope);
        if(now.isBefore(scope.validFrom())||!now.isBefore(scope.validUntil()))return deny(request,"RELATIONSHIP_EXPIRED","共享关系不在授权有效期内",identity.subjectId(),scope);
        var fields=db.query("SELECT field_name,data_type,time_field FROM resource_field WHERE resource_id=?",(r,n)->List.of(r.getString(1),r.getString(2),Boolean.toString(r.getBoolean(3))),scope.resource());
        var names=new HashSet<String>();fields.forEach(f->names.add(f.get(0)));
        boolean validTime=fields.stream().anyMatch(f->f.get(0).equals(scope.timeField())&&f.get(2).equals("true")&&f.get(1).toLowerCase(Locale.ROOT).matches("(datetime|timestamp|date)(\\(.*\\))?"));
        if(scope.fields().isEmpty()||scope.fields().contains("*")||new HashSet<>(scope.fields()).size()!=scope.fields().size()||!names.containsAll(scope.fields())||!validTime
                ||!scope.dataFrom().isBefore(scope.dataUntil()))return deny(request,"POLICY_UNAVAILABLE","策略字段或时间条件已不可用，请重新确认资源元数据",identity.subjectId(),scope);
        List<String> selected=request.requestedFields()==null?scope.fields():request.requestedFields();
        List<String> filters=request.filterFields()==null?List.of():request.filterFields();
        if(selected.isEmpty()||new HashSet<>(selected).size()!=selected.size()||!scope.fields().containsAll(selected)||!scope.fields().containsAll(filters)||new HashSet<>(filters).size()!=filters.size())
            return deny(request,"FIELDS_FORBIDDEN","请求返回字段或过滤字段超出授权范围",identity.subjectId(),scope);
        Instant from=request.from()==null?scope.dataFrom():request.from(),until=request.to()==null?scope.dataUntil():request.to();
        CallerCredentialService.sql(from);CallerCredentialService.sql(until);
        if(!from.isBefore(until)||from.isBefore(scope.dataFrom())||until.isAfter(scope.dataUntil()))return deny(request,"TIME_FORBIDDEN","请求的数据时间范围超出授权范围或起止不合法",identity.subjectId(),scope);
        final CapabilityService.RestRuntime runtime;
        try{runtime=capabilities.runtime(request.capabilityId());}
        catch(BusinessException ex){return deny(request,"CAPABILITY_UNAVAILABLE","接口能力或来源不可用",identity.subjectId(),scope);}
        if(!runtime.enabled())return deny(request,"CAPABILITY_UNAVAILABLE","接口能力、资源或来源不可用",identity.subjectId(),scope);
        var capability=capabilities.get(request.capabilityId());
        if(!scope.resource().equals(capability.resourceId()))return deny(request,"RELATIONSHIP_UNAVAILABLE","接口能力不属于共享资源",identity.subjectId(),scope);
        Instant checked=CallerCredentialService.now(),validUntil=scope.validUntil().isBefore(identity.expiresAt())?scope.validUntil():identity.expiresAt();
        if(!validUntil.isAfter(checked))return deny(request,"RELATIONSHIP_EXPIRED","调用凭据或共享关系已到期",identity.subjectId(),scope);
        return new Decision(true,"ALLOWED","共享访问已允许",request.relationshipId(),request.capabilityId(),identity.subjectId(),scope.resource(),scope.policy(),scope.version(),scope.policyVersion(),
                List.copyOf(selected),List.copyOf(filters),scope.timeField(),from,until,runtime.sourceHost(),runtime.sourcePort(),runtime.databaseName(),runtime.physicalTable(),checked,validUntil);
    }
    private List<String> readFields(String value){
        try{return List.copyOf(json.readValue(value,new TypeReference<List<String>>(){}));}
        catch(Exception e){throw new IllegalStateException("策略字段元数据不可用");}
    }
    private static Decision deny(AccessRequest r,String code,String reason){
        return deny(r,code,reason,null,null);
    }
    private static Decision deny(AccessRequest r,String code,String reason,String subject,Scope scope){
        // Identify only the authenticated caller and its own matched relation. Never expose another party's scope.
        return new Decision(false,code,reason,r.relationshipId(),r.capabilityId(),subject,scope==null?null:scope.resource(),scope==null?null:scope.policy(),scope==null?null:scope.version(),scope==null?null:scope.policyVersion(),List.of(),List.of(),null,null,null,null,null,null,null,CallerCredentialService.now(),null);
    }
}
