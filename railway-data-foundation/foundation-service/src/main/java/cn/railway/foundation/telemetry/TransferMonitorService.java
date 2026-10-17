package cn.railway.foundation.telemetry;

import cn.railway.foundation.api.*;
import cn.railway.foundation.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** One admin read model over immutable technical events; never joins receiver messages. */
@Service
public class TransferMonitorService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean receiverConfigured;
    public TransferMonitorService(JdbcTemplate db,ObjectMapper json,Environment env){
        this.db=db;this.json=json;receiverConfigured=!env.getProperty("FOUNDATION_RECEIVER_TELEMETRY_TOKEN","").isEmpty();
    }
    public record Filter(Instant from,Instant until,String method,String stage,String outcome,String resourceId,String taskId,String traceId){}
    private static final Set<String> STAGES=Set.of("REST_QUERY","KAFKA_SEND","KAFKA_STORE","KAFKA_CHECKPOINT","KAFKA_REPORT","KAFKA_TASK");
    private static final String RECEIVER_STAGE="CASE WHEN e.event_type='BATCH_STORED' THEN 'KAFKA_STORE' WHEN e.event_type LIKE 'OFFSET_%' THEN 'KAFKA_CHECKPOINT' WHEN e.event_type LIKE 'REPORT_%' THEN 'KAFKA_REPORT' ELSE 'KAFKA_TASK' END";
    private static final String STORAGE_DURATION="(SELECT c.storage_duration_ms FROM kafka_receiver_event c WHERE c.collector_id=e.collector_id AND c.task_id=e.task_id AND c.batch_id=e.batch_id AND c.event_type IN ('OFFSET_COMMITTED','OFFSET_UNCONFIRMED') AND c.storage_duration_ms IS NOT NULL ORDER BY c.log_id LIMIT 1)";
    // UNION is performed before filtering, counting and pagination. Fetching one page from each
    // existing endpoint then sorting in the browser would lose records and produce wrong totals.
    private static final String EVENTS="""
        SELECT CONCAT('runtime:',e.event_id) entry_id,'RUNTIME' origin,e.event_id,NULL receiver_log_id,
        e.completed_at occurred_at,e.received_at,e.collector_id,
        IF(e.collection_point='REST_PROVIDER','REST','KAFKA') method,
        IF(e.collection_point='REST_PROVIDER','REST_QUERY','KAFKA_SEND') stage,
        e.outcome COLLATE utf8mb4_unicode_ci outcome,e.reason_code COLLATE utf8mb4_unicode_ci reason_code,e.resource_id,r.resource_name,e.record_count,e.byte_count,e.duration_ms,
        IF(e.collection_point='REST_PROVIDER','REST_EXECUTION','KAFKA_SEND_EXECUTION') duration_scope,
        e.trace_id,NULL task_id,NULL grant_id,NULL batch_id,e.report_id,e.topic,e.topic_partition,e.topic_offset,
        NULL inserted_count,NULL duplicate_count,NULL operator_id,JSON_ARRAY() positions_json,NULL event_type
        FROM sharing_runtime_event e LEFT JOIN data_resource r ON r.resource_id=e.resource_id
        UNION ALL
        SELECT CONCAT('receiver:',e.collector_id,':',e.event_id),'RECEIVER',e.event_id,e.log_id,
        e.created_at,e.received_at,e.collector_id,'KAFKA',%s,
        e.outcome COLLATE utf8mb4_unicode_ci outcome,e.reason_code COLLATE utf8mb4_unicode_ci reason_code,e.resource_id,r.resource_name,
        IF(e.event_type='BATCH_STORED',e.inserted_count,NULL),e.byte_count,
        IF(e.event_type='BATCH_STORED',%s,NULL),
        IF(e.event_type='BATCH_STORED','DB_TRANSACTION',NULL),
        NULL,e.task_id,e.grant_id,e.batch_id,e.report_id,e.topic,NULL,NULL,
        e.inserted_count,e.duplicate_count,e.operator_id,e.positions_json,e.event_type
        FROM kafka_receiver_event e LEFT JOIN data_resource r ON r.resource_id=e.resource_id
        """.formatted(RECEIVER_STAGE,STORAGE_DURATION);

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(Filter f,int limit,int offset){
        Actor.requireAdmin();validate(f,limit,offset);
        var baseArgs=new ArrayList<Object>();baseArgs.add(utc(f.from()));baseArgs.add(utc(f.until()));
        String base="occurred_at>=? AND occurred_at<?";
        if(f.resourceId()!=null){base+=" AND resource_id=?";baseArgs.add(f.resourceId());}
        var args=new ArrayList<>(baseArgs);String where=base;
        for(var pair:List.of(new String[]{"method",f.method()},new String[]{"stage",f.stage()},new String[]{"outcome",f.outcome()},new String[]{"task_id",f.taskId()},new String[]{"trace_id",f.traceId()})){
            if(pair[1]!=null){where+=" AND "+pair[0]+"=?";args.add(pair[1]);}
        }
        String source=" FROM ("+EVENTS+") t WHERE ";
        long total=db.queryForObject("SELECT COUNT(*)"+source+where,Long.class,args.toArray());
        var summary=db.queryForMap("SELECT SUM(method='REST') rest_observed,SUM(stage='KAFKA_SEND') send_observed,SUM(origin='RECEIVER') receiver_observed,"
            +"SUM(stage='REST_QUERY') rest_calls,SUM(CASE WHEN stage='KAFKA_SEND' AND outcome='SUCCESS' THEN record_count ELSE 0 END) kafka_sent,"
            +"SUM(CASE WHEN stage='KAFKA_STORE' THEN inserted_count ELSE 0 END) received_records,SUM(outcome='ERROR') error_events"+source+base,baseArgs.toArray());
        // Summaries describe observed records only. No source reports => unknown, never a fake zero.
        if(number(summary,"rest_observed")==0)summary.put("rest_calls",null);
        if(number(summary,"send_observed")==0)summary.put("kafka_sent",null);
        if(number(summary,"receiver_observed")==0)summary.put("received_records",null);
        if(number(summary,"rest_observed")+number(summary,"send_observed")+number(summary,"receiver_observed")==0)summary.put("error_events",null);
        args.add(limit);args.add(offset);
        var items=db.queryForList("SELECT *"+source+where+" ORDER BY occurred_at DESC,entry_id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var item:items){
            try{item.put("positions",json.readTree(item.remove("positions_json").toString()));}
            catch(Exception ex){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"传输技术位置无法读取");}
            for(String field:List.of("occurred_at","received_at")){
                Object value=item.get(field);
                if(value instanceof LocalDateTime time)item.put(field,time.toInstant(ZoneOffset.UTC));
            }
        }
        return Map.of("items",items,"total",total,"summary",summary,"receiver_configured",receiverConfigured,"source","REPORTED_TECHNICAL_EVENTS");
    }
    private static long number(Map<String,Object> values,String name){return values.get(name) instanceof Number n?n.longValue():0;}
    private static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static void validate(Filter f,int limit,int offset){
        if(f.from()==null||f.until()==null||!f.from().isBefore(f.until())||Duration.between(f.from(),f.until()).compareTo(Duration.ofHours(24))>0||limit<1||limit>50||offset<0||offset>1000000)invalid();
        if(f.method()!=null&&!Set.of("REST","KAFKA").contains(f.method())||f.stage()!=null&&!STAGES.contains(f.stage())||f.outcome()!=null&&!Set.of("SUCCESS","DENIED","ERROR","INFO","WARNING").contains(f.outcome()))invalid();
        if(f.resourceId()!=null&&!f.resourceId().matches("RES_[a-f0-9]{32}")||f.taskId()!=null&&!f.taskId().matches("RCV_[a-f0-9]{32}")||f.traceId()!=null&&!f.traceId().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))invalid();
    }
    private static void invalid(){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"传输监测参数不合法，时间范围最多24小时");}
}
