package cn.railway.foundation.resource;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.metadata.ScanService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SchemaComparisonService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Scan(String taskId,String sourceId,String status,LocalDateTime startedAt,LocalDateTime finishedAt) {}
    public record Candidates(List<Scan> items,long total) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(int added,int removed,int modified,int unchanged) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Change(String changeType,String fieldName,ScanService.Field before,ScanService.Field after,List<String> changedProperties) {}
    public record TableChange(String property,String before,String after) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Comparison(String resourceId,int resourceVersion,String sourceId,String schemaName,String physicalTable,
            Scan baselineScan,Scan targetScan,String comparisonStatus,Summary summary,List<TableChange> tableChanges,List<Change> changes) {}
    private record Table(String schema,String name,String comment,int fieldCount) {}
    private final JdbcTemplate db;
    private final ResourceService resources;
    public SchemaComparisonService(JdbcTemplate db,ResourceService resources) { this.db=db;this.resources=resources; }

    public Candidates candidates(String resourceId,int limit,int offset) {
        var resource=resources.get(resourceId);var baseline=scan(resource.scanTaskId());validateBaseline(resource,baseline);
        String where=" source_id=? AND status='SUCCESS' AND started_at>=? AND task_id<>?";
        var items=db.query("SELECT * FROM metadata_scan_task WHERE"+where+" ORDER BY started_at DESC,task_id DESC LIMIT ? OFFSET ?",
                (rs,n)->new Scan(rs.getString("task_id"),rs.getString("source_id"),rs.getString("status"),
                        rs.getTimestamp("started_at").toLocalDateTime(),rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime()),
                resource.sourceId(),baseline.startedAt(),baseline.taskId(),limit,offset);
        long total=db.queryForObject("SELECT COUNT(*) FROM metadata_scan_task WHERE"+where,Long.class,
                resource.sourceId(),baseline.startedAt(),baseline.taskId());
        return new Candidates(items,total);
    }

    public Comparison compare(String resourceId,String targetId) {
        var resource=resources.get(resourceId);var baseline=scan(resource.scanTaskId());validateBaseline(resource,baseline);
        var target=scan(targetId);
        if (!resource.sourceId().equals(target.sourceId()) || !"SUCCESS".equals(target.status()) || target.finishedAt()==null
                || target.startedAt().isBefore(baseline.startedAt()) || target.taskId().equals(baseline.taskId()))
            throw invalid("请选择同一来源中不早于登记扫描的其他成功任务");
        // All reads use the same MVCC snapshot, including a task deleted concurrently.
        var beforeTables=completeTables(baseline.taskId());
        if(beforeTables.stream().anyMatch(table->!resource.schemaName().equals(table.schema())))
            throw invalid("登记扫描的数据库模式不一致，不能比较");
        var beforeTable=beforeTables.stream().filter(table->resource.physicalTable().equals(table.name())).findFirst().orElse(null);
        if(beforeTable==null) throw invalid("登记扫描缺少对应表，请核对资源来源");
        var before=fields(baseline.taskId(),beforeTable);
        var afterTables=completeTables(target.taskId());
        if(afterTables.isEmpty()) {
            return result(resource,baseline,target,"SCOPE_UNCONFIRMED",null,List.of(),List.of());
        }
        if(afterTables.stream().anyMatch(table->!resource.schemaName().equals(table.schema())))
            throw invalid("目标扫描的数据库模式与登记扫描不一致，不能比较");
        var afterTable=afterTables.stream().filter(table->resource.physicalTable().equals(table.name())).findFirst().orElse(null);
        if(afterTable==null) return result(resource,baseline,target,"TABLE_NOT_FOUND",null,List.of(),List.of());
        var after=fields(target.taskId(),afterTable);
        var oldByName=new HashMap<String,ScanService.Field>();before.forEach(field->oldByName.put(field.fieldName(),field));
        var newByName=new HashMap<String,ScanService.Field>();after.forEach(field->newByName.put(field.fieldName(),field));
        var changes=new ArrayList<Change>();int added=0,removed=0,modified=0,unchanged=0;
        for(var field:after) if(!oldByName.containsKey(field.fieldName())) {
            changes.add(new Change("ADDED",field.fieldName(),null,field,List.of()));added++;
        }
        for(var field:before) if(!newByName.containsKey(field.fieldName())) {
            changes.add(new Change("REMOVED",field.fieldName(),field,null,List.of()));removed++;
        }
        for(var field:after) {
            var old=oldByName.get(field.fieldName());if(old==null)continue;
            var properties=changedProperties(old,field);
            if(properties.isEmpty())unchanged++;
            else { changes.add(new Change("MODIFIED",field.fieldName(),old,field,properties));modified++; }
        }
        var tableChanges=Objects.equals(beforeTable.comment(),afterTable.comment())?List.<TableChange>of():
                List.of(new TableChange("table_comment",beforeTable.comment(),afterTable.comment()));
        return result(resource,baseline,target,changes.isEmpty()&&tableChanges.isEmpty()?"UNCHANGED":"CHANGED",
                new Summary(added,removed,modified,unchanged),tableChanges,List.copyOf(changes));
    }
    private Comparison result(ResourceService.Resource resource,Scan baseline,Scan target,String status,Summary summary,
            List<TableChange> tables,List<Change> changes) {
        return new Comparison(resource.resourceId(),resource.version(),resource.sourceId(),resource.schemaName(),resource.physicalTable(),
                baseline,target,status,summary,tables,changes);
    }
    private Scan scan(String id) {
        var rows=db.query("SELECT * FROM metadata_scan_task WHERE task_id=?",(rs,n)->new Scan(rs.getString("task_id"),
                rs.getString("source_id"),rs.getString("status"),rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toLocalDateTime()),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"扫描任务不存在，请刷新后重新选择");
        return rows.get(0);
    }
    private void validateBaseline(ResourceService.Resource resource,Scan scan) {
        if(!resource.sourceId().equals(scan.sourceId()) || !"SUCCESS".equals(scan.status()) || scan.finishedAt()==null)throw invalid("资源登记扫描来源或状态不一致");
    }
    private List<Table> tables(String task) {
        return db.query("SELECT * FROM metadata_scan_table WHERE task_id=? ORDER BY table_name",
                (rs,n)->new Table(rs.getString("schema_name"),rs.getString("table_name"),rs.getString("table_comment"),rs.getInt("field_count")),task);
    }
    private List<Table> completeTables(String task) {
        var tables=tables(task);
        int expectedTables=db.queryForObject("SELECT table_count FROM metadata_scan_task WHERE task_id=?",Integer.class,task);
        long expectedFields=tables.stream().mapToLong(Table::fieldCount).sum();
        long fields=db.queryForObject("SELECT COUNT(*) FROM metadata_scan_field WHERE task_id=?",Long.class,task);
        if(expectedTables!=tables.size() || expectedFields!=fields || tables.stream().anyMatch(table->table.fieldCount()<1))
            throw invalid("扫描快照数量不完整，请重新扫描后再比较");
        return tables;
    }
    private List<ScanService.Field> fields(String task,Table table) {
        var rows=db.query("SELECT * FROM metadata_scan_field WHERE task_id=? AND schema_name=? AND table_name=? ORDER BY ordinal_position,field_name",
                (rs,n)->new ScanService.Field(rs.getString("field_name"),rs.getString("data_type"),rs.getString("column_type"),
                        rs.getBoolean("nullable"),rs.getBoolean("primary_key"),rs.getString("default_value"),
                        rs.getString("technical_comment"),rs.getInt("ordinal_position")),task,table.schema(),table.name());
        var names=new HashSet<String>();var positions=new HashSet<Integer>();
        if(table.fieldCount()<1 || rows.size()!=table.fieldCount() || rows.stream().anyMatch(field->
                field.ordinalPosition()<1 || field.ordinalPosition()>rows.size() || !names.add(field.fieldName()) || !positions.add(field.ordinalPosition())))
            throw invalid("扫描字段快照不完整，请重新扫描后再比较");
        return rows;
    }
    private static List<String> changedProperties(ScanService.Field a,ScanService.Field b) {
        var changed=new ArrayList<String>();
        if(!Objects.equals(a.dataType(),b.dataType()))changed.add("data_type");
        if(!Objects.equals(a.columnType(),b.columnType()))changed.add("column_type");
        if(a.primaryKey()!=b.primaryKey())changed.add("primary_key");
        if(a.nullable()!=b.nullable())changed.add("nullable");
        if(!Objects.equals(a.defaultValue(),b.defaultValue()))changed.add("default_value");
        if(!Objects.equals(a.technicalComment(),b.technicalComment()))changed.add("technical_comment");
        if(a.ordinalPosition()!=b.ordinalPosition())changed.add("ordinal_position");
        return List.copyOf(changed);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_ARGUMENT,message); }
}
