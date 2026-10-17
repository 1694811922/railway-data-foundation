package cn.railway.foundation.resource;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
public class ResourceHistoryService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(String resourceId,int version,String action,String changedField,LocalDateTime recordedAt,String operationReason,String operatorName) {}
    public record Page(List<Entry> items,long total) {}
    public record Snapshot(Entry entry,ResourceService.Resource resource,List<ResourceService.Field> fields) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Detail(Snapshot snapshot,Snapshot previousSnapshot) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ResourceHistoryService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

    // MANDATORY makes snapshot failure roll back the corresponding Resource write.
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(ResourceService.Resource resource,List<ResourceService.Field> fields,String action,String changedField) {record(resource,fields,action,changedField,"");}
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(ResourceService.Resource resource,List<ResourceService.Field> fields,String action,String changedField,String reason) {
        try {
            db.update("INSERT INTO resource_revision(resource_id,version,action,changed_field,recorded_at,resource_snapshot,fields_snapshot,operator_name,operator_user_id,operation_reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    resource.resourceId(),resource.version(),action,changedField,LocalDateTime.now(ZoneOffset.UTC),
                    json.writeValueAsString(resource),json.writeValueAsString(fields),Actor.operator(),Actor.userId(),reason);
        }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"资源快照保存失败，本次修改未保存");}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(String id,int limit,int offset) {
        exists(id);
        var items=db.query("SELECT resource_id,version,action,changed_field,recorded_at,operation_reason,operator_name FROM resource_revision WHERE resource_id=? ORDER BY version DESC LIMIT ? OFFSET ?",
                (rs,n)->entry(rs),id,limit,offset);
        return new Page(items,db.queryForObject("SELECT COUNT(*) FROM resource_revision WHERE resource_id=?",Long.class,id));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(String id,int version) {
        exists(id);var snapshot=read(id,version);
        if(snapshot==null)throw new BusinessException(ErrorCode.NOT_FOUND,"该版本未留存，不能还原历史状态");
        // Only the immediately preceding version can represent this operation's changes.
        return new Detail(snapshot,version>1?read(id,version-1):null);
    }
    private void exists(String id) {
        if(db.queryForObject("SELECT COUNT(*) FROM data_resource WHERE resource_id=?",Integer.class,id)==0)
            throw new BusinessException(ErrorCode.NOT_FOUND,"资源不存在");
    }
    private Snapshot read(String id,int version) {
        var rows=db.query("SELECT * FROM resource_revision WHERE resource_id=? AND version=?",(rs,n)->{
            try {
                var resource=json.readValue(rs.getString("resource_snapshot"),ResourceService.Resource.class);
                var fields=json.readValue(rs.getString("fields_snapshot"),new TypeReference<List<ResourceService.Field>>(){});
                fields=fields.stream().sorted(Comparator.comparingInt(ResourceService.Field::ordinalPosition)).toList();
                if(!id.equals(resource.resourceId()) || version!=resource.version() || fields.stream().anyMatch(field->field.resourceVersion()!=version))
                    throw new IllegalStateException("Snapshot ownership mismatch");
                return new Snapshot(entry(rs),resource,fields);
            }catch(Exception error){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"历史快照读取失败，请重试或检查记录完整性");}
        },id,version);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Entry entry(ResultSet rs)throws SQLException {
        return new Entry(rs.getString("resource_id"),rs.getInt("version"),rs.getString("action"),rs.getString("changed_field"),rs.getTimestamp("recorded_at").toLocalDateTime(),rs.getString("operation_reason"),rs.getString("operator_name"));
    }
}
