package cn.railway.foundation.resource;

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
    public record Resource(String resourceId,String resourceName,String sourceId,String sourceName,String schemaName,
            String physicalTable,String resourceType,String businessDescription,String ownerDepartment,
            String securityLevel,String updateMode,String status,int version,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(String fieldName,String dataType,boolean primaryKey,String technicalComment,int ordinalPosition,
            String businessName,String businessDescription,boolean timeField,String sensitivityLevel) {}
    private final JdbcTemplate db;
    public ResourceService(JdbcTemplate db) { this.db=db; }

    public List<Resource> list(int limit,int offset) {
        return db.query("SELECT r.*,s.source_name FROM data_resource r JOIN source_system s ON s.source_id=r.source_id ORDER BY r.updated_at DESC,r.resource_id DESC LIMIT ? OFFSET ?",
                (rs,n)->resourceRow(rs),limit,offset);
    }
    public Resource get(String id) {
        var rows=db.query("SELECT r.*,s.source_name FROM data_resource r JOIN source_system s ON s.source_id=r.source_id WHERE r.resource_id=?",
                (rs,n)->resourceRow(rs),id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"资源不存在");
        return rows.get(0);
    }
    @Transactional
    public Resource create(ResourceRequest request) {
        // A resource must be grounded in a successfully scanned table, never an arbitrary client path.
        var scans=db.query("SELECT t.task_id FROM metadata_scan_task t JOIN metadata_scan_table m ON m.task_id=t.task_id WHERE t.source_id=? AND t.status='SUCCESS' AND m.schema_name=? AND m.table_name=? ORDER BY t.finished_at DESC LIMIT 1",
                (rs,n)->rs.getString(1),request.sourceId(),request.schemaName(),request.physicalTable());
        if (scans.isEmpty()) throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"请先扫描并选择该数据源中的表");
        String id="RES_"+UUID.randomUUID().toString().replace("-","");
        LocalDateTime now=now();
        try {
            db.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,resource_type,business_description,owner_department,security_level,update_mode,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?,?)",
                    id,request.sourceId(),scans.get(0),request.schemaName(),request.physicalTable(),request.resourceName().strip(),"TABLE",
                    request.businessDescription().strip(),request.ownerDepartment().strip(),request.securityLevel(),request.updateMode(),now,now);
        } catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.BUSINESS_ERROR,"该数据源中的表已经登记为资源"); }
        db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,technical_comment,ordinal_position) SELECT ?,field_name,column_type,primary_key,technical_comment,ordinal_position FROM metadata_scan_field WHERE task_id=? AND schema_name=? AND table_name=?",
                id,scans.get(0),request.schemaName(),request.physicalTable());
        return get(id);
    }
    @Transactional
    public Resource update(String id,ResourceRequest request) {
        var current=get(id);
        if (!current.sourceId().equals(request.sourceId()) || !current.schemaName().equals(request.schemaName())
                || !current.physicalTable().equals(request.physicalTable()))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"资源来源和物理表不可更改，请重新登记");
        db.update("UPDATE data_resource SET resource_name=?,business_description=?,owner_department=?,security_level=?,update_mode=?,updated_at=?,version=version+1 WHERE resource_id=?",
                request.resourceName().strip(),request.businessDescription().strip(),request.ownerDepartment().strip(),
                request.securityLevel(),request.updateMode(),now(),id);
        return get(id);
    }
    @Transactional
    public Resource publish(String id) {
        var current=get(id);
        if (!"DRAFT".equals(current.status())) throw new BusinessException(ErrorCode.BUSINESS_ERROR,"仅草稿资源可以发布");
        db.update("UPDATE data_resource SET status='PUBLISHED',version=version+1,updated_at=? WHERE resource_id=? AND status='DRAFT'",now(),id);
        return get(id);
    }
    public List<Field> fields(String id) {
        get(id);
        return db.query("SELECT * FROM resource_field WHERE resource_id=? ORDER BY ordinal_position",(rs,n)->fieldRow(rs),id);
    }
    @Transactional
    public Field updateField(String id,String fieldName,FieldUpdateRequest request) {
        get(id);
        String sensitivity=request.sensitivityLevel()==null?"":request.sensitivityLevel();
        int changed=db.update("UPDATE resource_field SET business_name=?,business_description=?,time_field=?,sensitivity_level=? WHERE resource_id=? AND field_name=?",
                request.businessName()==null?"":request.businessName().strip(),
                request.businessDescription()==null?"":request.businessDescription().strip(),request.timeField(),sensitivity,id,fieldName);
        if (changed==0) throw new BusinessException(ErrorCode.NOT_FOUND,"资源字段不存在");
        db.update("UPDATE data_resource SET updated_at=?,version=version+1 WHERE resource_id=?",now(),id);
        return db.query("SELECT * FROM resource_field WHERE resource_id=? AND field_name=?",(rs,n)->fieldRow(rs),id,fieldName).get(0);
    }
    private static Resource resourceRow(ResultSet rs) throws SQLException {
        return new Resource(rs.getString("resource_id"),rs.getString("resource_name"),rs.getString("source_id"),rs.getString("source_name"),
                rs.getString("schema_name"),rs.getString("physical_table"),rs.getString("resource_type"),rs.getString("business_description"),
                rs.getString("owner_department"),rs.getString("security_level"),rs.getString("update_mode"),rs.getString("status"),
                rs.getInt("version"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getTimestamp("updated_at").toLocalDateTime());
    }
    private static Field fieldRow(ResultSet rs) throws SQLException {
        return new Field(rs.getString("field_name"),rs.getString("data_type"),rs.getBoolean("primary_key"),
                rs.getString("technical_comment"),rs.getInt("ordinal_position"),rs.getString("business_name"),
                rs.getString("business_description"),rs.getBoolean("time_field"),rs.getString("sensitivity_level"));
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
