package cn.railway.demand;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.security.MessageDigest;
import java.time.Instant;

/** DB commit is the prerequisite for any Kafka offset commit. */
@Component
public class DurableInbox {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
    public DurableInbox(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json){this.db=db;this.tx=new TransactionTemplate(manager);this.json=json;}
    public long checkpoint(String task,String identity,int partition,long initial,long beginning,long end){return tx.execute(s->{
        lock(task,identity);var rows=db.queryForList("SELECT next_offset FROM receiving_checkpoint WHERE task_id=? AND partition_id=?",task,partition);
        long next=rows.isEmpty()?initial:((Number)rows.get(0).get("next_offset")).longValue();
        if(next<beginning||next>end)throw new ReceiverFailure(400,"保存的消费位置已失效，不能跳过缺失历史");
        if(rows.isEmpty())db.update("INSERT INTO receiving_checkpoint(task_id,partition_id,next_offset) VALUES(?,?,?)",task,partition,next);return next;
    });}
    private Map<String,Object> lock(String task,String identity){
        var row=db.queryForMap("SELECT grant_id,desired_state,topic_identity FROM receiving_task WHERE task_id=? FOR UPDATE",task);
        if(!"RUNNING".equals(row.get("desired_state")))throw new ReceiverFailure(400,"任务已停止");
        if(row.get("topic_identity")==null)db.update("UPDATE receiving_task SET topic_identity=? WHERE task_id=?",identity,task);
        else if(!identity.equals(row.get("topic_identity")))throw new ReceiverFailure(400,"主题已重建，禁止沿用原消费位置");return row;
    }
    public Map<Integer,Long> save(String task,String identity,List<ConsumerRecord<byte[],byte[]>> records,Instant started,Instant completed){return tx.execute(s->{
        var row=lock(task,identity);String grant=row.get("grant_id").toString();var next=new TreeMap<Integer,Long>();var observations=new ArrayList<Map<String,Object>>();
        for(var r:records){
            int size=r.value()==null?0:r.value().length;if(size>16777215||r.key()!=null&&r.key().length>16777215)throw new ReceiverFailure(400,"消息超过接收存储上限，未提交消费位置");
            var headers=new ArrayList<Map<String,Object>>();for(var h:r.headers()){var entry=new LinkedHashMap<String,Object>();entry.put("key",h.key());entry.put("value",h.value()==null?null:Base64.getEncoder().encodeToString(h.value()));headers.add(entry);}
            String headerJson=serialize(headers);String digest=digest(r,headerJson);
            var existing=db.queryForList("SELECT content_digest FROM received_message WHERE grant_id=? AND topic_identity=? AND partition_id=? AND message_offset=?",grant,identity,r.partition(),r.offset());
            if(existing.isEmpty()){
                db.update("INSERT INTO received_message(task_id,grant_id,topic_identity,topic,partition_id,message_offset,message_key,message_value,headers_json,kafka_timestamp,timestamp_type,content_digest,payload_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",task,grant,identity,r.topic(),r.partition(),r.offset(),r.key(),r.value(),headerJson,r.timestamp(),r.timestampType().name(),digest,size);
                observations.add(Map.of("partition",r.partition(),"offset",r.offset(),"byte_count",size));
            }else if(!digest.equals(existing.get(0).get("content_digest")))throw new ReceiverFailure(400,"同一消息位置内容不一致，任务已阻塞");
            next.merge(r.partition(),r.offset()+1,Math::max);
        }
        for(var n:next.entrySet())db.update("INSERT INTO receiving_checkpoint(task_id,partition_id,next_offset) VALUES(?,?,?) ON DUPLICATE KEY UPDATE next_offset=GREATEST(next_offset,VALUES(next_offset))",task,n.getKey(),n.getValue());
        if(!observations.isEmpty()){
            String report=UUID.randomUUID().toString();String body=serialize(Map.of("report_id",report,"started_at",started.toString(),"completed_at",completed.toString(),"positions",observations));
            db.update("INSERT INTO receiving_outbox(report_id,task_id,report_json) VALUES(?,?,?)",report,task,body);
            db.update("UPDATE receiving_task SET last_received_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE task_id=?",task);
        }
        return next;
    });}
    String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new ReceiverFailure(400,"消息元信息无法保存");}}
    static String digest(ConsumerRecord<byte[],byte[]> r,String headers){try{var d=MessageDigest.getInstance("SHA-256");for(byte[] value:new byte[][]{r.key(),r.value(),headers.getBytes(java.nio.charset.StandardCharsets.UTF_8),Long.toString(r.timestamp()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),r.timestampType().name().getBytes(java.nio.charset.StandardCharsets.US_ASCII)}){d.update(java.nio.ByteBuffer.allocate(4).putInt(value==null?-1:value.length).array());if(value!=null)d.update(value);}return HexFormat.of().formatHex(d.digest());}catch(Exception e){throw new IllegalStateException("消息摘要计算失败");}}
}
