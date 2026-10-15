package cn.railway.demand;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Read-only diagnosis using the task's own subscription identity. Never changes its state. */
@Service
public class ReceivingProgressService {
    private final JdbcTemplate db;private final FoundationClient foundation;private final SecretVault vault;private final KafkaConnections kafka;private final ObjectMapper json;private final TransactionTemplate snapshot;
    public ReceivingProgressService(JdbcTemplate db,FoundationClient foundation,SecretVault vault,KafkaConnections kafka,ObjectMapper json,PlatformTransactionManager manager){this.db=db;this.foundation=foundation;this.vault=vault;this.kafka=kafka;this.json=json;snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    private record Snapshot(Map<String,Object> task,String context,String encrypted,String identity,Map<Integer,Long> checkpoints,Instant readAt) {}
    public Map<String,Object> progress(String id,String subject){
        var saved=snapshot.execute(status->{
            var rows=db.queryForList("SELECT "+ReceivingService.PUBLIC+",encrypted_secrets,context_json,topic_identity,(SELECT COUNT(*) FROM received_message m WHERE m.task_id=t.task_id) received_count FROM receiving_task t WHERE task_id=? AND subject_id=?",id,subject);
            if(rows.isEmpty())throw new ReceiverFailure(403,"无权查看该任务的接收进度");
            var row=new LinkedHashMap<>(rows.get(0));var checkpoints=new HashMap<Integer,Long>();
            db.query("SELECT partition_id,next_offset FROM receiving_checkpoint WHERE task_id=?",r->{checkpoints.put(r.getInt(1),r.getLong(2));},id);
            return new Snapshot(row,row.remove("context_json").toString(),row.remove("encrypted_secrets").toString(),(String)row.remove("topic_identity"),Map.copyOf(checkpoints),Instant.now());
        });
        if(saved.identity()==null||saved.identity().isBlank())throw new ReceiverFailure(400,"任务缺少已确认主题身份，无法安全核对接收进度");
        try{
            var ctx=json.readTree(saved.context());var secrets=json.readTree(vault.open(id,saved.encrypted()));
            ReceivingService.sameContext(ctx,foundation.worker(saved.task().get("grant_id").toString(),secrets.path("token").asText()));
            var positions=kafka.positions(ctx,secrets.path("password").asText());
            if(saved.identity()!=null&&!saved.identity().equals(positions.identity()))throw new ReceiverFailure(400,"主题身份已变化，原检查点不能用于当前主题");
            var result=compare(positions,saved.checkpoints());result.put("task",saved.task());result.put("checkpoint_read_at",saved.readAt().toString());result.put("checked_at",Instant.now().toString());return result;
        }catch(ReceiverFailure e){throw e;}catch(Exception e){throw new ReceiverFailure(503,"接收进度查询失败，请重试");}
    }
    static Map<String,Object> compare(KafkaConnections.Positions positions,Map<Integer,Long> checkpoints){
        var parts=new ArrayList<Map<String,Object>>();boolean invalid=false,missing=false,empty=true;long total=0;
        for(var entry:new TreeMap<>(positions.partitions()).entrySet()){
            int partition=entry.getKey();var p=entry.getValue();Long next=checkpoints.get(partition),remaining=null;String state;
            if(next==null){state="NOT_INITIALIZED";missing=true;}
            else if(next<p.earliest()){state="HISTORY_EXPIRED";invalid=true;}
            else if(next>p.latest()){state="POSITION_INVALID";invalid=true;}
            else{remaining=p.latest()-next;total=Math.addExact(total,remaining);state=remaining>0?"BACKLOG":"CAUGHT_UP";}
            empty=empty&&p.earliest()==p.latest();var row=new LinkedHashMap<String,Object>();row.put("partition",partition);row.put("earliest_offset",p.earliest());row.put("latest_offset",p.latest());row.put("next_offset",next);row.put("committed_offset",p.committed());row.put("remaining_positions",remaining);row.put("status",state);parts.add(row);
        }
        if(parts.isEmpty())throw new ReceiverFailure(503,"主题分区信息为空，无法判断接收进度");
        String status=invalid?"POSITION_INVALID":missing?"NOT_INITIALIZED":empty?"NO_AVAILABLE_MESSAGES":total>0?"BACKLOG":"CAUGHT_UP";
        var summary=new LinkedHashMap<String,Object>();summary.put("status",status);summary.put("partition_count",parts.size());summary.put("remaining_positions",invalid||missing?null:total);
        var result=new LinkedHashMap<String,Object>();result.put("topic",positions.topic());result.put("summary",summary);result.put("partitions",parts);return result;
    }
}
