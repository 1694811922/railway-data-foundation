package cn.railway.foundation.resource;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ResourceService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Resource(String resourceId,String resourceName,String sourceId,String sourceName,String scanTaskId,String schemaName,
            String physicalTable,String resourceType,String businessDescription,String ownerDepartment,
            String securityLevel,String updateMode,String status,int version,LocalDateTime createdAt,LocalDateTime updatedAt,String providerSubjectId) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(String fieldName,String dataType,boolean primaryKey,String technicalComment,int ordinalPosition,
            String businessName,String businessDescription,boolean timeField,String sensitivityLevel,int resourceVersion) {}
    private final JdbcTemplate db;
    private final WorkspaceGate gate;
    private final ResourceHistoryService history;
    public ResourceService(JdbcTemplate db,ResourceHistoryService history,WorkspaceGate gate) { this.db=db; this.gate=gate;this.history=history; }

    public List<Resource> list(int limit,int offset) {
        return db.query("SELECT r.*,s.source_name,s.provider_subject_id FROM data_resource r JOIN source_system s ON s.source_id=r.source_id WHERE "+Actor.resource("r")+" ORDER BY r.updated_at DESC,r.resource_id DESC LIMIT ? OFFSET ?",
                (rs,n)->resourceRow(rs),Actor.args(limit,offset));
    }
    public Resource get(String id) {
        var rows=db.query("SELECT r.*,s.source_name,s.provider_subject_id FROM data_resource r JOIN source_system s ON s.source_id=r.source_id WHERE r.resource_id=?",
                (rs,n)->resourceRow(rs),id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"资源不存在");
        return rows.get(0);
    }
    @Transactional
    public Resource create(ResourceRequest request) {
        gate.source(request.sourceId());gate.scan(request.scanTaskId());
        // Use the exact reviewed snapshot. Lock its parent before copying or deleting any child metadata.
        if (request.scanTaskId()==null)
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"请选择明确的扫描任务，不能自动使用最新扫描");
        record ScanOrigin(String sourceId,String status) {}
        var scans=db.query("SELECT source_id,status FROM metadata_scan_task WHERE task_id=? FOR UPDATE",
                (rs,n)->new ScanOrigin(rs.getString("source_id"),rs.getString("status")),request.scanTaskId());
        if (scans.isEmpty() || !request.sourceId().equals(scans.get(0).sourceId()) || !"SUCCESS".equals(scans.get(0).status()))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"所选扫描任务不存在、来源不一致或尚未成功，请重新选择");
        var tables=db.query("SELECT field_count FROM metadata_scan_table WHERE task_id=? AND schema_name=? AND table_name=?",
                (rs,n)->rs.getInt(1),request.scanTaskId(),request.schemaName(),request.physicalTable());
        if (tables.size()!=1 || tables.get(0)<1)
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"所选表不在该任务的完整扫描结果中，请重新选择");
        String id="RES_"+UUID.randomUUID().toString().replace("-","");
        LocalDateTime now=now();
        try {
            db.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,resource_type,business_description,owner_department,security_level,update_mode,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?,?)",
                    id,request.sourceId(),request.scanTaskId(),request.schemaName(),request.physicalTable(),request.resourceName().strip(),"TABLE",
                    request.businessDescription().strip(),request.ownerDepartment().strip(),request.securityLevel(),request.updateMode(),now,now);
        } catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.BUSINESS_ERROR,"该数据源中的表已经登记为资源"); }
        int copied=db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,technical_comment,ordinal_position) SELECT ?,field_name,column_type,primary_key,technical_comment,ordinal_position FROM metadata_scan_field WHERE task_id=? AND schema_name=? AND table_name=?",
                id,request.scanTaskId(),request.schemaName(),request.physicalTable());
        if (copied!=tables.get(0))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"所选扫描的字段快照不完整，资源未保存，请重新扫描");
        return capture(id,"CREATE",null);
    }
    @Transactional
    public Resource update(String id,ResourceRequest request) {
        lockResource(id);
        var current=get(id);
        if("RETIRED".equals(current.status()))throw new BusinessException(ErrorCode.BUSINESS_ERROR,"已退役资源只读，不能修改");
        requireVersion(request.version(),current.version());
        if (!current.sourceId().equals(request.sourceId()) || !current.schemaName().equals(request.schemaName())
                || !current.physicalTable().equals(request.physicalTable())
                || request.scanTaskId()!=null && !current.scanTaskId().equals(request.scanTaskId()))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"资源来源、扫描任务和物理表不可更改，请重新登记");
        int changed=db.update("UPDATE data_resource SET resource_name=?,business_description=?,owner_department=?,security_level=?,update_mode=?,updated_at=?,version=version+1 WHERE resource_id=? AND version=?",
                request.resourceName().strip(),request.businessDescription().strip(),request.ownerDepartment().strip(),
                request.securityLevel(),request.updateMode(),now(),id,current.version());
        if (changed!=1) throw conflict();
        return capture(id,"RESOURCE_UPDATE",null);
    }
    public List<Field> fields(String id) {
        get(id);
        // A single read returns field attributes and their parent version from the same database snapshot.
        return db.query("SELECT f.*,r.version AS resource_version FROM resource_field f JOIN data_resource r ON r.resource_id=f.resource_id WHERE f.resource_id=? ORDER BY f.ordinal_position",(rs,n)->fieldRow(rs),id);
    }
    @Transactional
    public Field updateField(String id,String fieldName,FieldUpdateRequest request) {
        lockResource(id);
        var current=get(id);
        if("RETIRED".equals(current.status()))throw new BusinessException(ErrorCode.BUSINESS_ERROR,"已退役资源只读，不能修改");
        requireVersion(request.resourceVersion(),current.version());
        String sensitivity=request.sensitivityLevel()==null?"":request.sensitivityLevel();
        int changed=db.update("UPDATE resource_field SET business_name=?,business_description=?,time_field=?,sensitivity_level=? WHERE resource_id=? AND field_name=?",
                request.businessName()==null?"":request.businessName().strip(),
                request.businessDescription()==null?"":request.businessDescription().strip(),request.timeField(),sensitivity,id,fieldName);
        if (changed==0) throw new BusinessException(ErrorCode.NOT_FOUND,"资源字段不存在");
        if (db.update("UPDATE data_resource SET updated_at=?,version=version+1 WHERE resource_id=? AND version=?",now(),id,current.version())!=1)
            throw conflict();
        capture(id,"FIELD_UPDATE",fieldName);
        return db.query("SELECT f.*,r.version AS resource_version FROM resource_field f JOIN data_resource r ON r.resource_id=f.resource_id WHERE f.resource_id=? AND f.field_name=?",(rs,n)->fieldRow(rs),id,fieldName).get(0);
    }
    @Transactional
    public void delete(String id,int version) {
        lockResource(id);
        var current=get(id);
        requireVersion(version,current.version());
        if (!"DRAFT".equals(current.status()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅草稿资源可以删除；已发布资源须先下架再退役，保留历史");
        Integer capabilities=db.queryForObject("SELECT COUNT(*) FROM capability WHERE resource_id=?",Integer.class,id);
        if (capabilities!=null && capabilities>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"资源已登记接口能力，请先处理关联能力");
        db.update("DELETE FROM resource_field WHERE resource_id=?",id);
        if (db.update("DELETE FROM data_resource WHERE resource_id=? AND status='DRAFT'",id)!=1)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"资源状态已变化，请刷新后重试");
    }
    private void lockResource(String id) {
        // All resource writes lock the parent first, including lifecycle operations.
        var rows=db.query("SELECT resource_id FROM data_resource WHERE resource_id=? FOR UPDATE",(rs,n)->rs.getString(1),id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"资源不存在");
    }
    private Resource capture(String id,String action,String field) {
        var resource=get(id);history.record(resource,fields(id),action,field);return resource;
    }
    private static void requireVersion(Integer expected,int actual) {
        if (expected==null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"请提交读取时的资源版本，刷新后重新编辑");
        if (expected!=actual) throw conflict();
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.VERSION_CONFLICT,"资源已被修改，请刷新后重新编辑；本次修改未保存"); }
    private static Resource resourceRow(ResultSet rs) throws SQLException {
        return new Resource(rs.getString("resource_id"),rs.getString("resource_name"),rs.getString("source_id"),rs.getString("source_name"),rs.getString("scan_task_id"),
                rs.getString("schema_name"),rs.getString("physical_table"),rs.getString("resource_type"),rs.getString("business_description"),
                rs.getString("owner_department"),rs.getString("security_level"),rs.getString("update_mode"),rs.getString("status"),
                rs.getInt("version"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getString("provider_subject_id"));
    }
    private static Field fieldRow(ResultSet rs) throws SQLException {
        return new Field(rs.getString("field_name"),rs.getString("data_type"),rs.getBoolean("primary_key"),
                rs.getString("technical_comment"),rs.getInt("ordinal_position"),rs.getString("business_name"),
                rs.getString("business_description"),rs.getBoolean("time_field"),rs.getString("sensitivity_level"),rs.getInt("resource_version"));
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
