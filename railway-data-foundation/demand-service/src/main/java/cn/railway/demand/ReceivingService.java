package cn.railway.demand;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ReceivingService {
    static final String PUBLIC="task_id,grant_id,subject_id,task_name,start_position,desired_state,runtime_state,error_reason,version,last_received_at,created_at,updated_at,JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.grant.topic')) topic,JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.grant.resource_name')) resource_name,JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.grant.grant_name')) grant_name";
    private final JdbcTemplate db;private final FoundationClient foundation;private final SecretVault vault;private final KafkaConnections kafka;private final ObjectMapper json;private final TransactionTemplate tx;private final TransferLogs logs;
    public ReceivingService(JdbcTemplate db,FoundationClient foundation,SecretVault vault,KafkaConnections kafka,ObjectMapper json,PlatformTransactionManager manager,TransferLogs logs){this.db=db;this.foundation=foundation;this.vault=vault;this.kafka=kafka;this.json=json;this.logs=logs;tx=new TransactionTemplate(manager);}
    public Map<String,Object> get(String id,String subject){var rows=db.queryForList("SELECT "+PUBLIC+",(SELECT COUNT(*) FROM received_message m WHERE m.task_id=t.task_id) received_count,(SELECT COUNT(*) FROM receiving_outbox o WHERE o.task_id=t.task_id AND o.report_state='PENDING') pending_reports FROM receiving_task t WHERE task_id=? AND subject_id=?",id,subject);if(rows.isEmpty())throw new ReceiverFailure(403,"无权访问该接收任务");return rows.get(0);}
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> page(String subject,String keyword,String grantId,int limit,int offset){
        String where="subject_id=? AND (task_name LIKE ? OR grant_id LIKE ?)";String pattern="%"+keyword+"%";
        var args=new ArrayList<Object>(List.of(subject,pattern,pattern));
        if(grantId!=null){where+=" AND grant_id=?";args.add(grantId);}
        long total=db.queryForObject("SELECT COUNT(*) FROM receiving_task WHERE "+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        return Map.of("items",db.queryForList("SELECT "+PUBLIC+",(SELECT COUNT(*) FROM received_message m WHERE m.task_id=t.task_id) received_count FROM receiving_task t WHERE "+where+" ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?",args.toArray()),"total",total);
    }
    public Map<String,Object> create(ReceivingController.Create body,HttpServletRequest request){
        var user=ReceiverSecurity.identity(request);var ctx=foundation.context(body.grantId(),request);var checked=foundation.worker(body.grantId(),body.consumptionToken());
        sameContext(ctx,checked);
        if(!user.path("subject_id").asText().equals(ctx.path("grant").path("demand_subject_id").asText()))throw new ReceiverFailure(403,"不能接收其他主体的数据");
        kafka.verify(ctx,body.password());String actualIdentity=kafka.identity(ctx,body.password());String id="RCV_"+UUID.randomUUID().toString().replace("-","");
        String sealed=vault.seal(id,stringify(Map.of("password",body.password(),"token",body.consumptionToken())));
        try{tx.executeWithoutResult(s->{db.update("INSERT INTO receiving_task(task_id,grant_id,subject_id,created_by,task_name,context_json,encrypted_secrets,start_position,topic_identity) VALUES(?,?,?,?,?,?,?,?,?)",id,body.grantId(),user.path("subject_id").asText(),user.path("user_id").asText(),body.taskName().trim(),stringify(ctx),sealed,body.startPosition(),actualIdentity);logs.event(id,"CREATED","INFO","TASK_CREATED",user.path("user_id").asText());});}
        catch(org.springframework.dao.DuplicateKeyException e){throw new ReceiverFailure(409,"该授权已有接收任务，请打开已有任务");}
        return get(id,user.path("subject_id").asText());
    }
    public Map<String,Object> action(String id,String subject,String action,int version){
        return action(id,subject,action,version,null);
    }
    public Map<String,Object> action(String id,String subject,String action,int version,String operator){
        tx.executeWithoutResult(s->{
            get(id,subject);var row=db.queryForMap("SELECT * FROM receiving_task WHERE task_id=? FOR UPDATE",id);
            if(((Number)row.get("version")).intValue()!=version)throw new ReceiverFailure(409,"任务已变化，请刷新后操作");
            if(!action.equals("stop")){
                if("STOPPING".equals(row.get("runtime_state")))throw new ReceiverFailure(409,"任务正在停止，请等待连接释放后启动");
                var ctx=read(row.get("context_json").toString());var secret=read(vault.open(id,row.get("encrypted_secrets").toString()));sameContext(ctx,foundation.worker(row.get("grant_id").toString(),secret.path("token").asText()));
                kafka.verify(ctx,secret.path("password").asText());
                if("RUNNING".equals(row.get("desired_state"))&&!"BLOCKED".equals(row.get("runtime_state")))throw new ReceiverFailure(400,"任务已在运行或等待重试");
            }
            db.update("UPDATE receiving_task SET desired_state=?,runtime_state=?,error_reason='',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE task_id=?",action.equals("stop")?"STOPPED":"RUNNING",action.equals("stop")?"STOPPING":"STARTING",id);
            logs.event(id,action.equals("stop")?"STOP_REQUESTED":action.equals("resume")?"RESUME_REQUESTED":"START_REQUESTED","INFO","TASK_OPERATION",operator);
        });return get(id,subject);
    }
    public Map<String,Object> credentials(String id,int version,ReceivingController.Create body,HttpServletRequest request){
        String subject=ReceiverSecurity.identity(request).path("subject_id").asText();get(id,subject);
        var ctx=foundation.context(body.grantId(),request);sameContext(ctx,foundation.worker(body.grantId(),body.consumptionToken()));kafka.verify(ctx,body.password());String identity=kafka.identity(ctx,body.password());
        tx.executeWithoutResult(s->{var row=db.queryForMap("SELECT * FROM receiving_task WHERE task_id=? FOR UPDATE",id);
            if(!subject.equals(row.get("subject_id"))||!subject.equals(ctx.path("grant").path("demand_subject_id").asText())||!body.grantId().equals(row.get("grant_id")))throw new ReceiverFailure(403,"不能修改其他主体的接收配置");
            if(((Number)row.get("version")).intValue()!=version)throw new ReceiverFailure(409,"任务已变化，请刷新后操作");
            if(!"STOPPED".equals(row.get("desired_state"))||!"STOPPED".equals(row.get("runtime_state")))throw new ReceiverFailure(400,"请停止任务并等待连接释放后更新凭据");
            if(row.get("topic_identity")!=null&&!identity.equals(row.get("topic_identity")))throw new ReceiverFailure(400,"主题身份不同，不能覆盖原接收任务");
            db.update("UPDATE receiving_task SET context_json=?,encrypted_secrets=?,version=version+1,error_reason='',updated_at=UTC_TIMESTAMP(3) WHERE task_id=?",stringify(ctx),vault.seal(id,stringify(Map.of("password",body.password(),"token",body.consumptionToken()))),id);
            logs.event(id,"CREDENTIALS_UPDATED","INFO","CREDENTIALS_REPLACED",ReceiverSecurity.identity(request).path("user_id").asText());
        });return get(id,subject);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> messages(String id,String subject,String topic,Integer partition,String from,String until,int limit,int offset){
        get(id,subject);StringBuilder where=new StringBuilder("task_id=?");var args=new ArrayList<Object>();args.add(id);
        if(topic!=null&&!topic.isBlank()){where.append(" AND topic=?");args.add(topic);}
        if(partition!=null){where.append(" AND partition_id=?");args.add(partition);}
        for(var value:List.of(Map.entry("from",from==null?"":from),Map.entry("until",until==null?"":until))){if(!value.getValue().isBlank()){try{var time=java.time.Instant.parse(value.getValue());where.append(value.getKey().equals("from")?" AND received_at>=?":" AND received_at<?");args.add(java.sql.Timestamp.from(time));}catch(Exception e){throw new ReceiverFailure(400,"接收时间格式不正确");}}}
        long total=db.queryForObject("SELECT COUNT(*) FROM received_message WHERE "+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        return Map.of("items",db.queryForList("SELECT message_id,topic,partition_id,message_offset,kafka_timestamp,timestamp_type,payload_bytes,received_at,message_value IS NULL AS tombstone FROM received_message WHERE "+where+" ORDER BY received_at DESC,message_id DESC LIMIT ? OFFSET ?",args.toArray()),"total",total);
    }
    public Map<String,Object> message(String id,String subject,long message){get(id,subject);var rows=db.queryForList("SELECT * FROM received_message WHERE task_id=? AND message_id=?",id,message);if(rows.isEmpty())throw new ReceiverFailure(404,"消息不存在");var row=rows.get(0);row.put("message_key",representation((byte[])row.get("message_key")));row.put("message_value",representation((byte[])row.get("message_value")));return row;}
    static Map<String,Object> representation(byte[] bytes){if(bytes==null)return Map.of("encoding","NULL","bytes",0);try{var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);return Map.of("encoding","UTF8","content",decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),"bytes",bytes.length);}catch(Exception e){return Map.of("encoding","BASE64","content",Base64.getEncoder().encodeToString(bytes),"bytes",bytes.length);}}
    public static void sameContext(JsonNode stored,JsonNode fresh){
        for(String key:List.of("cluster_id","bootstrap_servers","security_protocol","sasl_mechanism"))if(!stored.path(key).equals(fresh.path(key)))throw new ReceiverFailure(403,"连接配置已变化，接收任务已阻塞");
        for(String key:List.of("grant_id","demand_subject_id","topic","consumer_group","broker_username"))if(!stored.path("grant").path(key).equals(fresh.path("grant").path(key)))throw new ReceiverFailure(403,"授权身份已变化，接收任务已阻塞");
        if(!fresh.path("grant").path("broker_access_confirmed").asBoolean())throw new ReceiverFailure(403,"授权已不可用");
    }
    JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new ReceiverFailure(400,"任务元信息无效");}}
    String stringify(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new ReceiverFailure(400,"接收参数格式不正确");}}
}
