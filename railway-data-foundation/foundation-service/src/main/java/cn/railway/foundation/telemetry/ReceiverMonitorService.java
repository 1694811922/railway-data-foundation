package cn.railway.foundation.telemetry;

import cn.railway.foundation.api.*;
import cn.railway.foundation.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Service-authenticated, client-reported technical observations; no Kafka/receiver data access. */
@Service
public class ReceiverMonitorService {
    private final JdbcTemplate db;private final ObjectMapper json;private final Validator validator;private final String token,node;
    public ReceiverMonitorService(JdbcTemplate db,ObjectMapper json,Validator validator,Environment env){this.db=db;this.json=json;this.validator=validator;
        token=env.getProperty("FOUNDATION_RECEIVER_TELEMETRY_TOKEN","");node=env.getProperty("RECEIVER_TELEMETRY_NODE_ID","receiver-local");
        if(!node.matches("[A-Za-z0-9_-]{1,64}")||!token.isEmpty()&&!token.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalStateException("接收监测技术配置不合法");
    }
    public void authenticate(String candidate){
        if(token.isEmpty())throw new BusinessException(ErrorCode.ACCESS_UNAVAILABLE,"接收监测上报尚未配置");
        if(candidate==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),candidate.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(ErrorCode.UNAUTHENTICATED,"接收监测技术凭据无效");
    }
    @Transactional
    public Map<String,Object> accept(ReceiverMonitorRequest request){
        if(request==null||!validator.validate(request).isEmpty())invalid();
        var ids=new HashSet<String>();int accepted=0,duplicates=0;
        for(var e:request.events()){
            validate(e);if(!ids.add(e.eventId()))invalid();
            var grants=db.queryForList("SELECT resource_id,capability_id,provider_subject_id,demand_subject_id,topic FROM kafka_subscription_grant WHERE grant_id=?",e.grantId());
            if(grants.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"订阅授权记录不存在");
            var grant=grants.get(0);String positions=encode(e.positions().stream().sorted(Comparator.comparing(ReceiverMonitorRequest.Position::partition)).toList());
            String original=encode(List.of(node,e.eventId(),e.taskId(),e.grantId(),Arrays.asList(e.batchId(),e.reportId(),e.eventType(),e.outcome(),e.reasonCode(),e.operatorId(),e.recordCount(),e.insertedCount(),e.duplicateCount(),e.byteCount(),positions,e.createdAt())));
            String digest=hash(e.storageDurationMs()==null?original:encode(List.of(original,"storage_duration_ms",e.storageDurationMs())));
            int created=db.update("INSERT IGNORE INTO kafka_receiver_event(collector_id,event_id,task_id,grant_id,resource_id,capability_id,provider_subject_id,demand_subject_id,topic,batch_id,report_id,event_type,outcome,reason_code,operator_id,record_count,inserted_count,duplicate_count,byte_count,positions_json,created_at,content_digest,storage_duration_ms,received_at) VALUES("+"?,".repeat(22)+"?,UTC_TIMESTAMP(3))",
                node,e.eventId(),e.taskId(),e.grantId(),grant.get("resource_id"),grant.get("capability_id"),grant.get("provider_subject_id"),grant.get("demand_subject_id"),grant.get("topic"),e.batchId(),e.reportId(),e.eventType(),e.outcome(),e.reasonCode(),e.operatorId(),e.recordCount(),e.insertedCount(),e.duplicateCount(),e.byteCount(),positions,java.time.LocalDateTime.ofInstant(e.createdAt(),java.time.ZoneOffset.UTC),digest,e.storageDurationMs());
            if(created==1)accepted++;else{
                String old=db.queryForObject("SELECT content_digest FROM kafka_receiver_event WHERE collector_id=? AND event_id=?",String.class,node,e.eventId());
                if(!digest.equals(old))throw new BusinessException(ErrorCode.VERSION_CONFLICT,"技术事件编号已存在且内容不同");duplicates++;
            }
        }
        return Map.of("event_ids",request.events().stream().map(ReceiverMonitorRequest.Event::eventId).toList(),"accepted",accepted,"duplicates",duplicates);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(String grant,String task,String event,String outcome,String from,String until,int limit,int offset){
        Actor.requireAdmin();if(limit<1||limit>50||offset<0||offset>1000000)invalid();
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();clauses.add("1=1");
        filter(clauses,args,"e.grant_id",grant,"KSG_[a-f0-9]{32}");filter(clauses,args,"e.task_id",task,"RCV_[a-f0-9]{32}");
        if(event!=null){if(!REASONS.containsKey(event))invalid();clauses.add("e.event_type=?");args.add(event);}
        if(outcome!=null){if(!Set.of("INFO","WARNING","ERROR","SUCCESS").contains(outcome))invalid();clauses.add("e.outcome=?");args.add(outcome);}
        Instant start=parse(from),end=parse(until);if(start!=null&&end!=null&&!start.isBefore(end))invalid();
        if(start!=null){clauses.add("e.created_at>=?");args.add(java.time.LocalDateTime.ofInstant(start,java.time.ZoneOffset.UTC));}if(end!=null){clauses.add("e.created_at<?");args.add(java.time.LocalDateTime.ofInstant(end,java.time.ZoneOffset.UTC));}
        String where=" FROM kafka_receiver_event e WHERE "+String.join(" AND ",clauses);
        long total=db.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        var rows=db.queryForList("SELECT e.log_id,e.event_id,e.collector_id,e.task_id,e.grant_id,e.resource_id,e.capability_id,e.provider_subject_id,e.demand_subject_id,e.topic,e.batch_id,e.report_id,e.event_type,e.outcome,e.reason_code,e.operator_id,e.record_count,e.inserted_count,e.duplicate_count,e.byte_count,e.positions_json,e.created_at,e.received_at,"+
            "e.task_id task_name,"+
            "CASE WHEN e.batch_id IS NULL THEN NULL WHEN EXISTS(SELECT 1 FROM kafka_receiver_event c WHERE c.collector_id=e.collector_id AND c.task_id=e.task_id AND c.batch_id=e.batch_id AND c.event_type='OFFSET_COMMITTED') THEN 'CONFIRMED' WHEN EXISTS(SELECT 1 FROM kafka_receiver_event c WHERE c.collector_id=e.collector_id AND c.task_id=e.task_id AND c.batch_id=e.batch_id AND c.event_type='OFFSET_UNCONFIRMED') THEN 'UNCONFIRMED' ELSE 'UNKNOWN' END commit_state,"+
            "CASE WHEN e.report_id IS NULL THEN NULL WHEN EXISTS(SELECT 1 FROM kafka_receiver_event c WHERE c.collector_id=e.collector_id AND c.task_id=e.task_id AND c.report_id=e.report_id AND c.event_type='REPORT_SENT') THEN 'SENT' WHEN EXISTS(SELECT 1 FROM kafka_receiver_event c WHERE c.collector_id=e.collector_id AND c.task_id=e.task_id AND c.report_id=e.report_id AND c.event_type='REPORT_REJECTED') THEN 'REJECTED' ELSE 'UNKNOWN' END report_state"+where+" ORDER BY e.created_at DESC,e.log_id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var row:rows){Object value=row.remove("positions_json");try{row.put("positions",json.readTree(value.toString()));}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"技术位置记录无法读取");}}
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("total",total);result.put("configured",!token.isEmpty());result.put("source","RECEIVER_SELF_REPORTED");
        result.put("last_received_at",db.queryForObject("SELECT MAX(received_at) FROM kafka_receiver_event",java.time.LocalDateTime.class));return result;
    }
    private static final Map<String,Set<String>> REASONS=Map.ofEntries(
        Map.entry("CREATED",Set.of("TASK_CREATED")),Map.entry("START_REQUESTED",Set.of("TASK_OPERATION")),Map.entry("RESUME_REQUESTED",Set.of("TASK_OPERATION")),Map.entry("STOP_REQUESTED",Set.of("TASK_OPERATION")),Map.entry("CREDENTIALS_UPDATED",Set.of("CREDENTIALS_REPLACED")),
        Map.entry("STARTING",Set.of("RECEIVER_STARTING")),Map.entry("RUNNING",Set.of("RECEIVER_READY")),Map.entry("STOPPED",Set.of("RECEIVER_STOPPED")),Map.entry("RETRYING",Set.of("RECEIVER_RETRYING","CONTROL_UNAVAILABLE")),Map.entry("BLOCKED",Set.of("RECEIVER_BLOCKED","AUTHORIZATION_INVALID","CONTEXT_INVALID","KAFKA_AUTH_FAILED","OFFSET_EXPIRED","RETRIES_EXHAUSTED")),
        Map.entry("BATCH_STORED",Set.of("DATABASE_COMMITTED")),Map.entry("OFFSET_COMMITTED",Set.of("KAFKA_COMMIT_CONFIRMED")),Map.entry("OFFSET_UNCONFIRMED",Set.of("KAFKA_COMMIT_UNKNOWN")),Map.entry("REPORT_SENT",Set.of("SUMMARY_ACKNOWLEDGED")),Map.entry("REPORT_RETRYING",Set.of("SUMMARY_PENDING")),Map.entry("REPORT_REJECTED",Set.of("SUMMARY_REJECTED")));
    private void validate(ReceiverMonitorRequest.Event e){
        if(!REASONS.getOrDefault(e.eventType(),Set.of()).contains(e.reasonCode()))invalid();
        String expected=Set.of("BATCH_STORED","OFFSET_COMMITTED","REPORT_SENT").contains(e.eventType())?"SUCCESS":"BLOCKED".equals(e.eventType())?"ERROR":Set.of("RETRYING","OFFSET_UNCONFIRMED","REPORT_RETRYING","REPORT_REJECTED").contains(e.eventType())?"WARNING":"INFO";
        if(!expected.equals(e.outcome())||e.createdAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))||e.createdAt().isAfter(Instant.now().plusSeconds(5))||e.createdAt().getNano()%1000000!=0)invalid();
        boolean batch="BATCH_STORED".equals(e.eventType()),position=batch||e.eventType().startsWith("OFFSET_");
        if(position&&(e.batchId()==null||e.positions().isEmpty()))invalid();
        if(e.storageDurationMs()!=null&&!e.eventType().startsWith("OFFSET_"))invalid();
        if(e.eventType().startsWith("REPORT_")&&e.reportId()==null)invalid();
        if(batch){if(e.recordCount()==null||e.insertedCount()==null||e.duplicateCount()==null||e.byteCount()==null||e.recordCount()==0||e.insertedCount()+e.duplicateCount()!=e.recordCount())invalid();}
        else if(e.recordCount()!=null||e.insertedCount()!=null||e.duplicateCount()!=null||e.byteCount()!=null)invalid();
        if(!position&&!e.positions().isEmpty())invalid();var partitions=new HashSet<Long>();
        for(var p:e.positions()){
            if(!partitions.add(p.partition()))invalid();
            if(batch){if(p.firstOffset()==null||p.lastOffset()==null||p.firstOffset()>p.lastOffset()||p.nextOffset()<=p.lastOffset())invalid();}
            else if(p.firstOffset()!=null||p.lastOffset()!=null)invalid();
        }
    }
    private void filter(List<String> clauses,List<Object> args,String column,String value,String pattern){if(value!=null){if(!value.matches(pattern))invalid();clauses.add(column+"=?");args.add(value);}}
    private static Instant parse(String value){if(value==null)return null;try{return Instant.parse(value);}catch(Exception e){invalid();return null;}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"技术事件格式不正确");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
    private static void invalid(){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,"接收技术事件或查询参数不合法");}
}
