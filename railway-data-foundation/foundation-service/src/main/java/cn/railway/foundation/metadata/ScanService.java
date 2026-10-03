package cn.railway.foundation.metadata;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.source.SourceEntity;
import cn.railway.foundation.source.SourceMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ScanService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Task(String taskId, String sourceId, String sourceName, String status, int tableCount,
            int fieldCount, LocalDateTime startedAt, LocalDateTime finishedAt, String failureReason) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Table(String taskId, String schemaName, String tableName, String tableComment,
            int fieldCount, Long estimatedRows, String status) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(String fieldName, String dataType, String columnType, boolean nullable,
            boolean primaryKey, String defaultValue, String technicalComment, int ordinalPosition) {}
    private final JdbcTemplate db;
    private final SourceMapper sources;
    private final MetadataAdapterFactory adapters;
    private final TransactionTemplate transactions;
    public ScanService(JdbcTemplate db, SourceMapper sources, MetadataAdapterFactory adapters, TransactionTemplate transactions) {
        this.db=db; this.sources=sources; this.adapters=adapters; this.transactions=transactions;
    }

    public Task start(String sourceId) {
        SourceEntity source = sources.selectById(sourceId);
        if (source == null) throw new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在");
        if ("DISABLED".equals(source.status)) throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"数据源已停用，请先启用后再扫描");
        String id = "SCAN_" + UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,started_at) VALUES(?,?,'RUNNING',?)",
                id, sourceId, now());
        try {
            var adapter=adapters.create(source);
            var tables=adapter.scanTables();
            var discovered=new ArrayList<List<FieldMetadata>>();
            for (var table:tables) discovered.add(adapter.scanFields(table.schema(),table.tableName()));
            transactions.executeWithoutResult(status -> {
                for (int i=0;i<tables.size();i++) {
                    var table=tables.get(i);
                    var fields=discovered.get(i);
                    db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,estimated_rows,field_count) VALUES(?,?,?,?,?,?)",
                            id,table.schema(),table.tableName(),table.tableComment(),table.estimatedRows(),fields.size());
                    for (var field:fields) db.update("INSERT INTO metadata_scan_field(task_id,schema_name,table_name,field_name,data_type,column_type,nullable,primary_key,default_value,technical_comment,ordinal_position) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            id,table.schema(),table.tableName(),field.columnName(),field.dataType(),field.columnType(),field.nullable(),field.primaryKey(),field.defaultValue(),field.columnComment(),field.ordinalPosition());
                }
                db.update("UPDATE metadata_scan_task SET status='SUCCESS',table_count=?,finished_at=? WHERE task_id=?",tables.size(),now(),id);
            });
        } catch (RuntimeException ex) {
            // Persist a safe failure state. Driver messages may contain host names or credentials.
            db.update("UPDATE metadata_scan_task SET status='FAILED',finished_at=?,failure_reason=? WHERE task_id=?",
                    now(),ex instanceof BusinessException ? ex.getMessage() : "元数据读取失败，请检查数据源连接和元数据权限",id);
        }
        return task(id);
    }

    public List<Task> list(int limit,int offset) {
        return db.query("SELECT t.*,s.source_name,(SELECT COALESCE(SUM(st.field_count),0) FROM metadata_scan_table st WHERE st.task_id=t.task_id) AS field_count FROM metadata_scan_task t JOIN source_system s ON s.source_id=t.source_id ORDER BY t.started_at DESC,t.task_id DESC LIMIT ? OFFSET ?",
                (rs,n)->taskRow(rs),limit,offset);
    }
    public Task task(String id) {
        var rows=db.query("SELECT t.*,s.source_name,(SELECT COALESCE(SUM(st.field_count),0) FROM metadata_scan_table st WHERE st.task_id=t.task_id) AS field_count FROM metadata_scan_task t JOIN source_system s ON s.source_id=t.source_id WHERE t.task_id=?",
                (rs,n)->taskRow(rs),id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"扫描任务不存在");
        return rows.get(0);
    }
    private Task taskRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Task(rs.getString("task_id"),rs.getString("source_id"),rs.getString("source_name"),rs.getString("status"),
                rs.getInt("table_count"),rs.getInt("field_count"),rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime(),rs.getString("failure_reason"));
    }
    public List<Table> tables(String taskId) {
        task(taskId);
        return db.query("SELECT t.*,CASE WHEN r.resource_id IS NULL THEN '待注册' ELSE '已注册' END AS table_status FROM metadata_scan_table t LEFT JOIN data_resource r ON r.source_id=(SELECT source_id FROM metadata_scan_task WHERE task_id=t.task_id) AND r.schema_name=t.schema_name AND r.physical_table=t.table_name WHERE t.task_id=? ORDER BY t.table_name",
                (rs,n)->new Table(rs.getString("task_id"),rs.getString("schema_name"),rs.getString("table_name"),rs.getString("table_comment"),
                        rs.getInt("field_count"),rs.getObject("estimated_rows")==null?null:rs.getLong("estimated_rows"),rs.getString("table_status")),taskId);
    }
    public List<Field> fields(String taskId,String tableName) {
        task(taskId);
        var table=db.query("SELECT 1 FROM metadata_scan_table WHERE task_id=? AND table_name=?",(rs,n)->1,taskId,tableName);
        if (table.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"扫描表不存在");
        return db.query("SELECT * FROM metadata_scan_field WHERE task_id=? AND table_name=? ORDER BY ordinal_position",
                (rs,n)->new Field(rs.getString("field_name"),rs.getString("data_type"),rs.getString("column_type"),
                        rs.getBoolean("nullable"),rs.getBoolean("primary_key"),rs.getString("default_value"),
                        rs.getString("technical_comment"),rs.getInt("ordinal_position")),taskId,tableName);
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
