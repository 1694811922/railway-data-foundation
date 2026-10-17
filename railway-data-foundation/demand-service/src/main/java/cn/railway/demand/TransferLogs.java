package cn.railway.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Fixed technical events only; never persist exception text, payloads or secrets. */
@Service
public class TransferLogs {
    static final Set<String> EVENTS=Set.of("CREATED","START_REQUESTED","RESUME_REQUESTED","STOP_REQUESTED","CREDENTIALS_UPDATED","STARTING","RUNNING","STOPPED","RETRYING","BLOCKED","BATCH_STORED","OFFSET_COMMITTED","OFFSET_UNCONFIRMED","REPORT_SENT","REPORT_RETRYING","REPORT_REJECTED");
    private final JdbcTemplate db;private final ObjectMapper json;private final TransferJournal journal;
    public TransferLogs(JdbcTemplate db,ObjectMapper json,TransferJournal journal){this.db=db;this.json=json;this.journal=journal;}
    public void event(String task,String event,String outcome,String reason,String operator){
        if(!EVENTS.contains(event)||!Set.of("SUCCESS","INFO","WARNING","ERROR").contains(outcome))throw new IllegalArgumentException("日志事件不合法");
        journal.append("INSERT INTO receiving_transfer_log(task_id,event_type,outcome,reason_code,operator_id) VALUES(?,?,?,?,?)",task,event,outcome,reason,operator);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(String subject,String task,String event,String outcome,String from,String until,int limit,int offset){
        if(limit<1||limit>50||offset<0||offset>1000000)throw new ReceiverFailure(400,"日志分页参数不正确");
        if(task!=null&&!task.matches("RCV_[a-f0-9]{32}"))throw new ReceiverFailure(400,"接收任务编号不正确");
        if(task!=null&&db.queryForObject("SELECT COUNT(*) FROM receiving_task WHERE task_id=? AND subject_id=?",Long.class,task,subject)==0)throw new ReceiverFailure(403,"无权访问该接收任务");
        String where="t.subject_id=?";var args=new ArrayList<Object>();args.add(subject);
        if(task!=null){where+=" AND l.task_id=?";args.add(task);}
        if(event!=null){if(!EVENTS.contains(event))throw new ReceiverFailure(400,"日志事件类型不正确");where+=" AND l.event_type=?";args.add(event);}
        if(outcome!=null){if(!Set.of("SUCCESS","INFO","WARNING","ERROR").contains(outcome))throw new ReceiverFailure(400,"日志结果不正确");where+=" AND l.outcome=?";args.add(outcome);}
        Instant start=parse(from),end=parse(until);
        if(start!=null&&end!=null&&!start.isBefore(end))throw new ReceiverFailure(400,"日志起止时间不正确");
        if(start!=null){where+=" AND l.created_at>=?";args.add(Timestamp.from(start));}
        if(end!=null){where+=" AND l.created_at<?";args.add(Timestamp.from(end));}
        String join=" FROM receiving_transfer_log l JOIN receiving_task t ON t.task_id=l.task_id WHERE "+where;
        long total=db.queryForObject("SELECT COUNT(*)"+join,Long.class,args.toArray());args.add(limit);args.add(offset);
        var items=db.queryForList("SELECT l.*,t.task_name,t.grant_id,JSON_UNQUOTE(JSON_EXTRACT(t.context_json,'$.grant.topic')) topic,"+
            "CASE WHEN l.batch_id IS NULL THEN NULL WHEN EXISTS(SELECT 1 FROM receiving_transfer_log c WHERE c.task_id=l.task_id AND c.batch_id=l.batch_id AND c.event_type='OFFSET_COMMITTED') THEN 'CONFIRMED' WHEN EXISTS(SELECT 1 FROM receiving_transfer_log c WHERE c.task_id=l.task_id AND c.batch_id=l.batch_id AND c.event_type='OFFSET_UNCONFIRMED') THEN 'UNCONFIRMED' ELSE 'UNKNOWN' END commit_state,"+
            "(SELECT o.report_state FROM receiving_outbox o WHERE o.task_id=l.task_id AND o.report_id=l.report_id) report_state"+join+" ORDER BY l.created_at DESC,l.log_id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var row:items){Object value=row.remove("positions_json");try{row.put("positions",value==null?List.of():json.readTree(value.toString()));}catch(Exception e){throw new ReceiverFailure(503,"传输位置日志无法读取");}}
        return Map.of("items",items,"total",total);
    }
    static Instant parse(String value){if(value==null)return null;try{return Instant.parse(value);}catch(Exception e){throw new ReceiverFailure(400,"日志时间格式不正确");}}
}
