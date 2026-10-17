package cn.railway.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Independent of grant credentials; a failed monitoring upload cannot undo message storage. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="demand.monitor.enabled",havingValue="true",matchIfMissing=true)
public class MonitorReporter {
    private final JdbcTemplate db;private final ObjectMapper json;private final FoundationClient foundation;private final String token;
    private static final List<String> FIELDS=List.of("task_id","grant_id","batch_id","report_id","event_type","outcome","reason_code","operator_id","record_count","inserted_count","duplicate_count","byte_count");
    public MonitorReporter(JdbcTemplate db,ObjectMapper json,FoundationClient foundation,Environment env){this.db=db;this.json=json;this.foundation=foundation;token=env.getProperty("DEMAND_TELEMETRY_TOKEN","");if(!token.isEmpty()&&!token.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalStateException("接收监测上报配置不合法");}
    @Scheduled(fixedDelay=3000)
    public void flush(){
        if(token.isEmpty())return;
        try{for(var row:db.queryForList("SELECT q.event_id,q.attempts,l.*,t.grant_id FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id JOIN receiving_task t ON t.task_id=l.task_id WHERE q.report_state='PENDING' AND q.next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY q.log_id LIMIT 20")){
            long log=((Number)row.get("log_id")).longValue();String id=row.get("event_id").toString();
            try{
                var e=new LinkedHashMap<String,Object>();e.put("event_id",id);for(String field:FIELDS)e.put(field,row.get(field));
                if(row.get("storage_duration_ms")!=null)e.put("storage_duration_ms",row.get("storage_duration_ms"));
                Object positions=row.get("positions_json");e.put("positions",positions==null?List.of():json.readTree(positions.toString()));
                Object time=row.get("created_at");e.put("created_at",(time instanceof LocalDateTime local?local.toInstant(ZoneOffset.UTC):((Timestamp)time).toInstant()).toString());
                var reply=foundation.monitor(token,json.writeValueAsString(Map.of("events",List.of(e))));
                if(reply.path("event_ids").size()!=1||!id.equals(reply.path("event_ids").get(0).asText())||reply.path("accepted").asInt(-1)+reply.path("duplicates").asInt(-1)!=1)throw new ReceiverFailure(503,"监测回执未确认");
                db.update("UPDATE receiving_monitor_outbox SET report_state='SENT',attempts=attempts+1,last_error='' WHERE log_id=? AND report_state='PENDING'",log);
            }catch(ReceiverFailure ex){
                boolean permanent=Set.of(400,404,409).contains(ex.status);int attempts=((Number)row.get("attempts")).intValue();long delay=Math.min(300,3L<<Math.min(attempts,7));
                db.update("UPDATE receiving_monitor_outbox SET report_state=?,attempts=attempts+1,last_error=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND) WHERE log_id=? AND report_state='PENDING'",permanent?"QUARANTINED":"PENDING",permanent?"EVENT_REJECTED":ex.status==401||ex.status==403?"AUTHENTICATION_FAILED":"DELIVERY_UNCONFIRMED",delay,log);
                if(!permanent)break;
            }catch(com.fasterxml.jackson.core.JsonProcessingException ex){db.update("UPDATE receiving_monitor_outbox SET report_state='QUARANTINED',attempts=attempts+1,last_error='EVENT_FORMAT_INVALID' WHERE log_id=?",log);}
        }}catch(org.springframework.dao.DataAccessException ignored){/* Keep queued data and retry after DB recovery. */}
    }
}
