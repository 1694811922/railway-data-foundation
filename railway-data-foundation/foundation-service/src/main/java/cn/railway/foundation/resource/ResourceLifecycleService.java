package cn.railway.foundation.resource;

import cn.railway.foundation.api.*;
import cn.railway.foundation.security.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Persist all governance intent atomically; the existing reconciler performs Broker work afterwards. */
@Service
public class ResourceLifecycleService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Impact(String resourceId,int resourceVersion,String status,long capabilities,long activeRestRelationships,long kafkaExecutions,long kafkaRevokePending,boolean canDelete){}
    public record Result(ResourceService.Resource resource,Impact impact){}
    private final JdbcTemplate db;private final ResourceService resources;private final ResourceHistoryService history;private final WorkspaceGate gate;
    public ResourceLifecycleService(JdbcTemplate db,ResourceService resources,ResourceHistoryService history,WorkspaceGate gate){this.db=db;this.resources=resources;this.history=history;this.gate=gate;}
    private void access(String id){if(Actor.demand())Actor.deny();gate.resource(id);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Impact impact(String id){access(id);var r=resources.get(id);
        long caps=count("SELECT COUNT(*) FROM capability WHERE resource_id=?",id);
        long rest=count("SELECT COUNT(*) FROM sharing_relationship WHERE resource_id=? AND status='ACTIVE'",id);
        long kafka=count("SELECT COUNT(*) FROM kafka_subscription_execution x JOIN kafka_subscription_grant g ON g.grant_id=x.grant_id WHERE g.resource_id=? AND x.execution_state<>'REVOKED'",id);
        long pending=count("SELECT COUNT(*) FROM kafka_subscription_execution x JOIN kafka_subscription_grant g ON g.grant_id=x.grant_id WHERE g.resource_id=? AND x.desired_state='REVOKED' AND x.execution_state<>'REVOKED'",id);
        return new Impact(id,r.version(),r.status(),caps,rest,kafka,pending,"DRAFT".equals(r.status())&&caps==0);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Result change(String id,String action,ResourceActionRequest request){
        access(id);ResourceLocks.lock(db,id);var current=resources.get(id);
        if(request==null||request.version()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>500)invalid("请提交资源版本和操作原因");
        if(request.version()!=current.version())throw new BusinessException(ErrorCode.VERSION_CONFLICT,"资源已变化，请刷新后重新确认操作");
        String target=switch(action){
            case "publish" -> "DRAFT".equals(current.status())?"PUBLISHED":null;
            case "unpublish" -> "PUBLISHED".equals(current.status())?"OFFLINE":null;
            case "republish" -> "OFFLINE".equals(current.status())?"PUBLISHED":null;
            case "retire" -> "OFFLINE".equals(current.status())?"RETIRED":null;
            default -> null;
        };
        if(target==null)invalid("当前资源状态不允许此操作；退役前须先下架，退役不可恢复");
        if("PUBLISHED".equals(target))validatePublish(current);
        var now=LocalDateTime.now(ZoneOffset.UTC);String reason=request.reason().strip();
        if(Set.of("OFFLINE","RETIRED").contains(target)){
            // Every approval/configuration path locks this same parent first, preventing a late grant.
            var relations=db.queryForList("SELECT relationship_id,status,version FROM sharing_relationship WHERE resource_id=? ORDER BY relationship_id FOR UPDATE",id);
            for(var row:relations){String before=row.get("status").toString();String after="RETIRED".equals(target)?"REVOKED":"ACTIVE".equals(before)?"SUSPENDED":before;
                if(after.equals(before))continue;int v=((Number)row.get("version")).intValue()+1;
                db.update("UPDATE sharing_relationship SET status=?,version=?,updated_at=? WHERE relationship_id=?",after,v,now,row.get("relationship_id"));
                db.update("INSERT INTO sharing_relationship_event(relationship_id,action,from_status,to_status,operator_name,operator_user_id,reason,version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",row.get("relationship_id"),"RETIRED".equals(target)?"RESOURCE_RETIRE":"RESOURCE_UNPUBLISH",before,after,Actor.operator(),Actor.userId(),reason,v,now);
            }
            var grants=db.queryForList("SELECT grant_id,status,version FROM kafka_subscription_grant WHERE resource_id=? ORDER BY grant_id FOR UPDATE",id);
            for(var row:grants){String grant=row.get("grant_id").toString(),before=row.get("status").toString();
                var execution=db.queryForList("SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=? FOR UPDATE",grant);
                boolean revoke=!execution.isEmpty()&&!"REVOKED".equals(execution.get(0).get("execution_state"));
                if(revoke)db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED',execution_state='REVOKE_PENDING',consumption_token_digest=NULL,encrypted_password=NULL,last_error='',updated_at=? WHERE grant_id=?",now,grant);
                String after="RETIRED".equals(target)?"REVOKED":before;
                if(!revoke&&after.equals(before))continue;int v=((Number)row.get("version")).intValue()+1;
                db.update("UPDATE kafka_subscription_grant SET status=?,version=?,updated_at=? WHERE grant_id=?",after,v,now,grant);
                db.update("INSERT INTO kafka_subscription_event(grant_id,action,from_status,to_status,whole_topic_acknowledged,operator_name,operator_user_id,reason,version,occurred_at,execution_state) VALUES(?,?,?,?,false,?,?,?,?,?,(SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=?))",grant,"RETIRED".equals(target)?"RESOURCE_RETIRE":"RESOURCE_UNPUBLISH",before,after,Actor.operator(),Actor.userId(),reason,v,now,grant);
            }
        }
        db.update("UPDATE data_resource SET status=?,version=version+1,updated_at=? WHERE resource_id=?",target,now,id);
        var resource=resources.get(id);history.record(resource,resources.fields(id),action.toUpperCase(Locale.ROOT),null,reason);
        return new Result(resource,impact(id));
    }
    private void validatePublish(ResourceService.Resource r){
        var source=db.queryForList("SELECT status FROM source_system WHERE source_id=? FOR UPDATE",r.sourceId());
        if(source.isEmpty()||!"ENABLED".equals(source.get(0).get("status")))invalid("来源系统已停用，不能发布资源");
        if(r.resourceName().isBlank()||r.businessDescription().isBlank()||r.ownerDepartment().isBlank()||!Set.of("公开","内部","敏感","机密").contains(r.securityLevel())||!Set.of("实时","每日","按需").contains(r.updateMode()))invalid("请先完善资源名称、业务描述、责任单位及分类信息");
        var expected=db.queryForList("SELECT t.field_count FROM metadata_scan_table t JOIN metadata_scan_task s ON s.task_id=t.task_id WHERE t.task_id=? AND s.source_id=? AND s.status='SUCCESS' AND t.schema_name=? AND t.table_name=?",r.scanTaskId(),r.sourceId(),r.schemaName(),r.physicalTable());
        if(count("SELECT COUNT(*) FROM resource_field WHERE resource_id=? AND (field_name='' OR data_type='' OR ordinal_position<1)",r.resourceId())>0)invalid("资源字段快照存在缺失属性，不能发布");
        if(expected.size()!=1||((Number)expected.get(0).get("field_count")).longValue()<1||count("SELECT COUNT(*) FROM resource_field WHERE resource_id=?",r.resourceId())!=((Number)expected.get(0).get("field_count")).longValue())invalid("资源登记字段快照不完整，不能发布；请核对登记快照");
    }
    private long count(String sql,String id){return db.queryForObject(sql,Long.class,id);}
    private static void invalid(String message){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message);}
}
