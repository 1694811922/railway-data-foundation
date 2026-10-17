package cn.railway.demand;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.http.*;
import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfEnvironmentVariable(named="RUN_DEMAND_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"demand.worker.enabled=false","demand.monitor.enabled=false","DEMAND_DB_NAME=demand_receiver_test"})
class ReceivingIntegrationTest {
    @Autowired JdbcTemplate db;@Autowired DurableInbox inbox;@Autowired ReceivingService service;@Autowired SecretVault vault;@Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager manager;
    @MockitoBean FoundationClient foundation;@MockitoBean KafkaConnections kafka;
    @Autowired ReceivingProgressService progress;
    @Autowired ReceivingDownloadService downloads;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ObjectMapper downloadJson;
    String task,grant,subject,password,token;JsonNode ctx;Instant now;
    @BeforeEach void fixture()throws Exception{
        task="RCV_"+UUID.randomUUID().toString().replace("-","");grant="KSG_"+UUID.randomUUID().toString().replace("-","");subject="SUB_"+UUID.randomUUID().toString().replace("-","");password="ks_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);token="rkc_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ctx=json.valueToTree(Map.of("grant",Map.of("grant_id",grant,"demand_subject_id",subject,"broker_username","rdfk_"+"a".repeat(32),"topic","topic","consumer_group","rdf.subscription."+"a".repeat(32),"broker_access_confirmed",true),"cluster_id","isolated-cluster","bootstrap_servers","127.0.0.1:9092","security_protocol","SASL_PLAINTEXT","sasl_mechanism","SCRAM-SHA-512"));
        db.update("INSERT INTO receiving_task(task_id,grant_id,subject_id,created_by,task_name,context_json,encrypted_secrets,start_position,desired_state,runtime_state) VALUES(?,?,?,'account','独立接收测试',?,?,'EARLIEST','RUNNING','RUNNING')",task,grant,subject,ctx.toString(),vault.seal(task,json.writeValueAsString(Map.of("password",password,"token",token))));
        when(foundation.identity(any())).thenAnswer(a->{jakarta.servlet.http.HttpServletRequest r=a.getArgument(0);if(!"test-login".equals(r.getHeader("X-Test-Login")))throw new ReceiverFailure(401,"未登录");if(!"GET".equals(r.getMethod())&&!"test-csrf".equals(r.getHeader("X-CSRF-TOKEN")))throw new ReceiverFailure(403,"校验失效");return json.valueToTree(Map.of("role","DEMAND","subject_id",Optional.ofNullable(r.getHeader("X-Test-Subject")).orElse(subject),"user_id","account"));});
        when(foundation.worker(anyString(),anyString())).thenReturn(ctx);when(foundation.context(anyString(),any())).thenReturn(ctx);
    }
    @AfterEach void cleanup(){for(String t:List.of("receiving_outbox","receiving_checkpoint","received_message"))db.update("DELETE FROM "+t+" WHERE task_id=?",task);db.update("DELETE FROM receiving_task WHERE task_id=?",task);}
    ConsumerRecord<byte[],byte[]> record(long offset,byte[] value){return new ConsumerRecord<>("topic",0,offset,1000,TimestampType.CREATE_TIME,-1,value==null?-1:value.length,new byte[0],value,new RecordHeaders().add("重复头",new byte[]{1}).add("重复头",null),Optional.empty());}
    @Test void losslessStorageCheckpointAndSummaryAreOneTransaction(){inbox.checkpoint(task,"cluster:topic",0,0,0,100);inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{(byte)255,0}),record(1,null)),now,now);assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(2);assertThat(db.queryForObject("SELECT next_offset FROM receiving_checkpoint WHERE task_id=?",Long.class,task)).isEqualTo(2);assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_outbox WHERE task_id=?",Long.class,task)).isEqualTo(1);var row=db.queryForMap("SELECT message_value,headers_json FROM received_message WHERE task_id=? AND message_offset=0",task);assertThat((byte[])row.get("message_value")).containsExactly((byte)255,(byte)0);assertThat(service.read(row.get("headers_json").toString()).size()).isEqualTo(2);assertThat(db.queryForObject("SELECT report_json FROM receiving_outbox WHERE task_id=?",String.class,task)).doesNotContain("message_value","重复头");}
    @Test void rereadDoesNotDuplicateDataOrReports(){var records=List.of(record(0,new byte[]{1}));inbox.save(task,"cluster:topic",records,now,now);inbox.save(task,"cluster:topic",records,now,now);assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_outbox WHERE task_id=?",Long.class,task)).isEqualTo(1);}
    @Test void mismatchedDuplicateRollsBackEntireBatch(){inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);assertThatThrownBy(()->inbox.save(task,"cluster:topic",List.of(record(1,new byte[]{3}),record(0,new byte[]{2})),now,now)).isInstanceOf(ReceiverFailure.class);assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(1);assertThat(db.queryForObject("SELECT next_offset FROM receiving_checkpoint WHERE task_id=?",Long.class,task)).isEqualTo(1);}
    @Test void outerRollbackLeavesNoDataCheckpointOrReport(){new TransactionTemplate(manager).executeWithoutResult(s->{inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);s.setRollbackOnly();});for(String t:List.of("received_message","receiving_checkpoint","receiving_outbox"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+t+" WHERE task_id=?",Long.class,task)).isZero();}
    @Test void topicRecreationMissingHistoryAndStoppedTaskRejectWrites(){inbox.checkpoint(task,"cluster:topic",0,4,0,10);assertThatThrownBy(()->inbox.checkpoint(task,"other-topic",0,0,0,10)).isInstanceOf(ReceiverFailure.class);assertThatThrownBy(()->inbox.checkpoint(task,"cluster:topic",0,0,5,10)).isInstanceOf(ReceiverFailure.class);db.update("UPDATE receiving_task SET desired_state='STOPPED' WHERE task_id=?",task);assertThatThrownBy(()->inbox.save(task,"cluster:topic",List.of(record(4,new byte[]{1})),now,now)).isInstanceOf(ReceiverFailure.class);}
    @Test void scopedCountsDetailsMessagesAndSecrets(){inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);assertThat(service.page("another-subject","",null,20,0).get("total")).isEqualTo(0L);assertThatThrownBy(()->service.get(task,"another-subject")).isInstanceOf(ReceiverFailure.class);assertThatThrownBy(()->service.messages(task,"another-subject",null,null,null,null,20,0)).isInstanceOf(ReceiverFailure.class);assertThat(service.stringify(service.get(task,subject))).doesNotContain(password,token,"encrypted_secrets","context_json");assertThat(service.messages(task,subject,"topic",0,null,null,1,0).get("total")).isEqualTo(1L);}
    @Test void grantFilterIsExactScopedAndPreservesHistoryWithoutKafkaChecks(){
        String decoy="RCV_"+UUID.randomUUID().toString().replace("-","");
        try{
            db.update("INSERT INTO receiving_task(task_id,grant_id,subject_id,created_by,task_name,context_json,encrypted_secrets,start_position) VALUES(?,?,?,'account',?,?,?,'EARLIEST')",decoy,"KSG_"+UUID.randomUUID().toString().replace("-",""),subject,grant,ctx.toString(),"unused-encrypted-value");
            db.update("UPDATE receiving_task SET runtime_state='BLOCKED',error_reason='授权已撤回' WHERE task_id=?",task);
            clearInvocations(foundation,kafka);
            var result=service.page(subject,"",grant,20,0);assertThat(result.get("total")).isEqualTo(1L);
            assertThat(service.stringify(result)).contains(task,"BLOCKED").doesNotContain(decoy,password,token,"encrypted_secrets","context_json");
            assertThat(service.page(subject,"不匹配关键词",grant,20,0).get("total")).isEqualTo(0L);
            assertThat(service.page(subject,"",grant,1,1).get("total")).isEqualTo(1L);assertThat((List<?>)service.page(subject,"",grant,1,1).get("items")).isEmpty();
            assertThat(service.page("other","",grant,20,0).get("total")).isEqualTo(0L);
            assertThat(service.page(subject,"","KSG_"+"0".repeat(32),20,0).get("total")).isEqualTo(0L);
            verifyNoInteractions(foundation,kafka);
        }finally{db.update("DELETE FROM receiving_task WHERE task_id=?",decoy);}
    }
    @Test void grantFilterHttpRequiresLoginValidatesInputAndDoesNotLeakOtherSubjectCounts(){
        String path="/api/v1/receiving-tasks?grantId="+grant;
        assertThat(http.getForEntity(path,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");h.set("X-Test-Subject","other");
        var other=http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(other.getBody().path("data").path("total").asInt()).isZero();assertThat(other.getBody().path("data").path("items").size()).isZero();
        h.set("X-Test-Subject",subject);var own=http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);assertThat(own.getBody().path("data").path("total").asInt()).isEqualTo(1);assertThat(own.getBody().path("data").path("items").get(0).path("task_id").asText()).isEqualTo(task);
        for(String value:List.of("bad","KSG_%",grant+"x",""))assertThat(http.exchange("/api/v1/receiving-tasks?grantId="+value,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void optimisticActionsRejectStaleVersionAndInvalidGrant(){assertThatThrownBy(()->service.action(task,subject,"stop",9)).isInstanceOf(ReceiverFailure.class);service.action(task,subject,"stop",1);assertThat(service.get(task,subject).get("version")).isEqualTo(2);assertThatThrownBy(()->service.action(task,subject,"start",2)).isInstanceOf(ReceiverFailure.class);db.update("UPDATE receiving_task SET runtime_state='STOPPED' WHERE task_id=?",task);when(foundation.worker(anyString(),anyString())).thenThrow(new ReceiverFailure(403,"已撤权"));assertThatThrownBy(()->service.action(task,subject,"start",2)).isInstanceOf(ReceiverFailure.class);assertThat(service.get(task,subject).get("version")).isEqualTo(2);}
    @Test void httpAuthenticationCsrfAndOwnershipAreEnforced(){assertThat(http.getForEntity("/api/v1/receiving-tasks",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);var h=new HttpHeaders();h.set("X-Test-Login","test-login");h.set("X-Test-Subject","another-subject");assertThat(http.exchange("/api/v1/receiving-tasks/"+task,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);h.set("X-Test-Subject",subject);assertThat(http.exchange("/api/v1/receiving-tasks/"+task+"/stop",HttpMethod.POST,new HttpEntity<>(Map.of("version",1),h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);h.set("X-CSRF-TOKEN","test-csrf");assertThat(http.exchange("/api/v1/receiving-tasks/"+task+"/stop",HttpMethod.POST,new HttpEntity<>(Map.of("version",1),h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);}
    @Test void credentialRenewalPreservesDataAndRejectsUnsafeChanges(){
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        db.update("UPDATE receiving_task SET desired_state='STOPPED',runtime_state='STOPPED' WHERE task_id=?",task);when(kafka.identity(any(),anyString())).thenReturn("cluster:topic");
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute("identity",json.valueToTree(Map.of("subject_id",subject,"user_id","account")));
        var body=new ReceivingController.Create("接收任务",grant,password,token,"EARLIEST");service.credentials(task,1,body,request);
        assertThat(service.get(task,subject).get("version")).isEqualTo(2);assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(1);
        assertThatThrownBy(()->service.credentials(task,1,body,request)).isInstanceOf(ReceiverFailure.class);
        when(kafka.identity(any(),anyString())).thenReturn("different-topic");assertThatThrownBy(()->service.credentials(task,2,body,request)).isInstanceOf(ReceiverFailure.class);assertThat(service.get(task,subject).get("version")).isEqualTo(2);
    }
    @Test void sessionUsesUtcForDefaultTimestampsAndOutboxScheduling(){inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);assertThat(db.queryForObject("SELECT ABS(TIMESTAMPDIFF(SECOND,received_at,UTC_TIMESTAMP(3))) FROM received_message WHERE task_id=?",Long.class,task)).isLessThan(5);assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_outbox WHERE task_id=? AND next_attempt_at<=UTC_TIMESTAMP(3)",Long.class,task)).isEqualTo(1);}
    @Test void progressIsScopedReadOnlyAndDoesNotReturnCredentials(){
        inbox.checkpoint(task,"cluster:topic",0,0,0,5);
        when(kafka.positions(any(),anyString())).thenReturn(new KafkaConnections.Positions("cluster:topic","topic",Map.of(0,new KafkaConnections.Position(0,5,null))));
        var before=db.queryForMap("SELECT * FROM receiving_task WHERE task_id=?",task);var checkpoint=db.queryForMap("SELECT * FROM receiving_checkpoint WHERE task_id=?",task);
        var result=progress.progress(task,subject);assertThat(((Map<?,?>)result.get("summary")).get("remaining_positions")).isEqualTo(5L);assertThat(service.stringify(result)).doesNotContain(password,token,"context_json","encrypted_secrets","broker_username");
        assertThat(db.queryForMap("SELECT * FROM receiving_task WHERE task_id=?",task)).isEqualTo(before);assertThat(db.queryForMap("SELECT * FROM receiving_checkpoint WHERE task_id=?",task)).isEqualTo(checkpoint);
        clearInvocations(kafka,foundation);assertThatThrownBy(()->progress.progress(task,"other")).isInstanceOf(ReceiverFailure.class);verifyNoInteractions(kafka,foundation);
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");h.set("X-Test-Subject","other");assertThat(http.exchange("/api/v1/receiving-tasks/"+task+"/progress",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/v1/receiving-tasks/"+task+"/progress",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void progressRefusesRevocationUnavailableBrokerAndRecreatedTopic(){
        db.update("UPDATE receiving_task SET topic_identity='cluster:topic' WHERE task_id=?",task);
        when(foundation.worker(anyString(),anyString())).thenThrow(new ReceiverFailure(403,"已撤权"));assertThatThrownBy(()->progress.progress(task,subject)).isInstanceOf(ReceiverFailure.class);verify(kafka,never()).positions(any(),anyString());
        doReturn(ctx).when(foundation).worker(anyString(),anyString());when(kafka.positions(any(),anyString())).thenThrow(new ReceiverFailure(503,"位置查询失败"));assertThatThrownBy(()->progress.progress(task,subject)).isInstanceOf(ReceiverFailure.class);
        doReturn(new KafkaConnections.Positions("cluster:new-topic","topic",Map.of(0,new KafkaConnections.Position(0,0,null)))).when(kafka).positions(any(),anyString());assertThatThrownBy(()->progress.progress(task,subject)).isInstanceOf(ReceiverFailure.class);assertThat(db.queryForObject("SELECT runtime_state FROM receiving_task WHERE task_id=?",String.class,task)).isEqualTo("RUNNING");
    }
    @Test void downloadsPreserveOriginalJsonBinaryEmptyAndNullWithOrderedHeaders()throws Exception{
        byte[] text="{\"内容\":\"中文\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),binary={(byte)255,0};
        inbox.save(task,"cluster:topic",List.of(record(0,text),record(1,null),record(2,binary),record(3,new byte[0])),now,now);
        var ids=db.queryForList("SELECT message_id FROM received_message WHERE task_id=? ORDER BY message_offset",Long.class,task);
        java.nio.file.Path temporary;
        try(var f=downloads.single(task,subject,ids.get(0),"content")){temporary=f.file;assertThat(java.nio.file.Files.readAllBytes(f.file)).containsExactly(text);assertThat(f.name).endsWith(".json");}
        assertThat(java.nio.file.Files.exists(temporary)).isFalse();
        try(var f=downloads.single(task,subject,ids.get(2),"content")){assertThat(java.nio.file.Files.readAllBytes(f.file)).containsExactly(binary);assertThat(f.name).endsWith(".bin");}
        try(var f=downloads.single(task,subject,ids.get(3),"content")){assertThat(f.bytes).isZero();assertThat(f.name).endsWith(".txt");}
        assertThatThrownBy(()->downloads.single(task,subject,ids.get(1),"content")).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(400));
        try(var f=downloads.single(task,subject,ids.get(1),"record")){var value=json.readTree(java.nio.file.Files.readAllBytes(f.file));assertThat(value.path("message_value").path("encoding").asText()).isEqualTo("NULL");assertThat(value.path("headers").size()).isEqualTo(2);assertThat(value.path("headers").get(1).path("value").isNull()).isTrue();}
        try(var f=downloads.export(task,subject,"topic",0,null,null)){assertThat(f.count).isEqualTo(4);var lines=java.nio.file.Files.readAllLines(f.file,java.nio.charset.StandardCharsets.UTF_8);assertThat(lines).hasSize(4);assertThat(json.readTree(lines.get(2)).path("message_value").path("encoding").asText()).isEqualTo("BASE64");assertThat(lines.toString()).doesNotContain(password,token,"encrypted_secrets");}
    }
    @Test void downloadHttpOwnershipAndFailureEnvelopesNeverProduceAttachments()throws Exception{
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);long message=db.queryForObject("SELECT message_id FROM received_message WHERE task_id=?",Long.class,task);
        String path="/api/v1/receiving-tasks/"+task+"/messages";
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");
        for(String suffix:List.of("/export","/"+message+"/download")){
            assertThat(http.getForEntity(path+suffix,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            h.set("X-Test-Subject","other");var forbidden=http.exchange(path+suffix,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(forbidden.getHeaders().containsKey("Content-Disposition")).isFalse();
        }
        h.set("X-Test-Subject",subject);var response=http.exchange(path+"/export",HttpMethod.GET,new HttpEntity<>(h),byte[].class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");assertThat(Long.parseLong(response.getHeaders().getFirst("X-Download-Bytes"))).isEqualTo(response.getBody().length);assertThat(json.readTree(response.getBody()).path("task_id").asText()).isEqualTo(task);
        for(String suffix:List.of("/export?topic=absent","/export?from=wrong","/export?from=2026-10-15T00:00:00Z&until=2026-10-14T00:00:00Z","/"+message+"/download?format=other")){var failure=http.exchange(path+suffix,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(failure.getBody().path("code").asInt()).isNotZero();assertThat(failure.getHeaders().containsKey("Content-Disposition")).isFalse();}
    }
    @Test void exportsExcludeNewArrivalsAndDoNotChangeTaskVersionOrCheckpoint()throws Exception{
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{if(call.getArgument(1) instanceof Map<?,?> row&&row.containsKey("message_value")&&once.compareAndSet(false,true)){java.util.concurrent.CompletableFuture.runAsync(()->inbox.save(task,"cluster:topic",List.of(record(1,new byte[]{2})),now,now)).get(5,java.util.concurrent.TimeUnit.SECONDS);}return call.callRealMethod();}).when(downloadJson).writeValue(any(java.io.OutputStream.class),any());
        try(var f=downloads.export(task,subject,null,null,null,null)){assertThat(f.count).isEqualTo(1);assertThat(java.nio.file.Files.readAllLines(f.file)).hasSize(1);}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(2);assertThat(service.get(task,subject).get("version")).isEqualTo(1);
        var before=db.queryForMap("SELECT * FROM receiving_checkpoint WHERE task_id=?",task);try(var f=downloads.export(task,subject,null,null,null,null)){assertThat(f.count).isEqualTo(2);}assertThat(db.queryForMap("SELECT * FROM receiving_checkpoint WHERE task_id=?",task)).isEqualTo(before);
    }
    @Test void exportRowLimitAndSlotAreReleasedAfterFailure()throws Exception{
        db.batchUpdate("INSERT INTO received_message(task_id,grant_id,topic_identity,topic,partition_id,message_offset,headers_json,kafka_timestamp,timestamp_type,content_digest,payload_bytes) VALUES(?,?,'cluster:topic','topic',0,?,'[]',0,'CREATE_TIME',?,0)",new org.springframework.jdbc.core.BatchPreparedStatementSetter(){public int getBatchSize(){return 10001;}public void setValues(java.sql.PreparedStatement ps,int i)throws java.sql.SQLException{ps.setString(1,task);ps.setString(2,grant);ps.setLong(3,i);ps.setString(4,"0".repeat(64));}});
        assertThatThrownBy(()->downloads.export(task,subject,null,null,null,null)).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(413));
        long message=db.queryForObject("SELECT MIN(message_id) FROM received_message WHERE task_id=?",Long.class,task);
        try(var f=downloads.single(task,subject,message,"record")){assertThatThrownBy(()->downloads.single(task,subject,message,"record")).isInstanceOfSatisfying(ReceiverFailure.class,e->assertThat(e.status).isEqualTo(429));f.close();}
        try(var f=downloads.single(task,subject,message,"record")){assertThat(f.count).isEqualTo(1);}
    }
    @Test void downloadRechecksLoginBeforeReturningAttachmentHeaders(){
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);var checks=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(checks.incrementAndGet()>1)throw new ReceiverFailure(401,"登录已失效");return json.valueToTree(Map.of("role","DEMAND","subject_id",subject,"user_id","account"));}).when(foundation).identity(any());
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");var response=http.exchange("/api/v1/receiving-tasks/"+task+"/messages/export",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(response.getHeaders().containsKey("Content-Disposition")).isFalse();assertThat(checks.get()).isEqualTo(2);assertThat(response.getBody().path("data").isNull()).isTrue();
    }

    @Autowired TransferLogs transferLogs;
    @Test void transferBatchSeparatesDurableStorageDedupAndOffsetAcknowledgement(){
        String batch=UUID.randomUUID().toString();
        inbox.save(task,"cluster:topic",List.of(record(0,"private-payload".getBytes())),now,now,batch);
        var data=transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0);
        var first=(Map<?,?>)((List<?>)data.get("items")).get(0);
        assertThat(first.get("inserted_count")).isEqualTo(1);assertThat(first.get("duplicate_count")).isEqualTo(0);assertThat(first.get("commit_state")).isEqualTo("UNKNOWN");
        assertThat(service.stringify(data)).doesNotContain(password,token,"private-payload","message_value","encrypted_secrets");
        inbox.offsetResult(task,batch,Map.of(0,1L),false);
        assertThat(service.stringify(transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0))).contains("UNCONFIRMED");
        inbox.offsetResult(task,batch,Map.of(0,1L),true);
        assertThat(service.stringify(transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0))).contains("CONFIRMED");
        inbox.save(task,"cluster:topic",List.of(record(0,"private-payload".getBytes())),now,now);
        var reread=(Map<?,?>)((List<?>)transferLogs.page(subject,task,"BATCH_STORED",null,null,null,1,0).get("items")).get(0);
        assertThat(reread.get("inserted_count")).isEqualTo(0);assertThat(reread.get("duplicate_count")).isEqualTo(1);
    }
    @Test void rollbackAndInconsistentMessagesCannotLeaveSuccessfulBatchLogs(){
        new TransactionTemplate(manager).executeWithoutResult(s->{inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);s.setRollbackOnly();});
        assertThat(transferLogs.page(subject,task,null,null,null,null,20,0).get("total")).isEqualTo(0L);
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        assertThatThrownBy(()->inbox.save(task,"cluster:topic",List.of(record(1,new byte[]{2}),record(0,new byte[]{3})),now,now)).isInstanceOf(ReceiverFailure.class);
        assertThat(transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0).get("total")).isEqualTo(1L);
    }
    @Test void statusTransitionsAreRecordedOnceAndPreserveRealOperator(){
        inbox.state(task,"RUNNING","","RECEIVER_READY");assertThat(transferLogs.page(subject,task,null,null,null,null,20,0).get("total")).isEqualTo(0L);
        inbox.state(task,"RETRYING","连接失败","RECEIVER_RETRYING");inbox.state(task,"RETRYING","连接失败","RECEIVER_RETRYING");
        assertThat(transferLogs.page(subject,task,null,null,null,null,20,0).get("total")).isEqualTo(1L);
        service.action(task,subject,"stop",1,"real-account-id");inbox.stopped(task);inbox.stopped(task);
        assertThat(transferLogs.page(subject,task,"STOPPED",null,null,null,20,0).get("total")).isEqualTo(1L);
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");h.set("X-CSRF-TOKEN","test-csrf");
        http.exchange("/api/v1/receiving-tasks/"+task+"/start",HttpMethod.POST,new HttpEntity<>(Map.of("version",2),h),JsonNode.class);
        assertThat(service.stringify(transferLogs.page(subject,task,"STOP_REQUESTED",null,null,null,20,0))).contains("real-account-id");
        assertThat(service.stringify(transferLogs.page(subject,task,"START_REQUESTED",null,null,null,20,0))).contains("account");
    }
    @Test void summaryRetriesAndAcknowledgementAreDistinctDurableEvents(){
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        String report=db.queryForObject("SELECT report_id FROM receiving_outbox WHERE task_id=?",String.class,task);
        inbox.reportResult(report,"PENDING");inbox.reportResult(report,"SENT");inbox.reportResult(report,"SENT");
        assertThat(db.queryForObject("SELECT attempts FROM receiving_outbox WHERE report_id=?",Integer.class,report)).isEqualTo(2);
        assertThat(transferLogs.page(subject,task,"REPORT_SENT",null,null,null,20,0).get("total")).isEqualTo(1L);
        assertThat(transferLogs.page(subject,task,"REPORT_RETRYING",null,null,null,20,0).get("total")).isEqualTo(1L);
        assertThat(service.stringify(transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0))).contains("SENT");
    }
    @Test void logPaginationFiltersAndHttpCountsAreSubjectScoped(){
        inbox.state(task,"RETRYING","连接失败","RECEIVER_RETRYING");inbox.state(task,"BLOCKED","处理失败","RETRIES_EXHAUSTED");
        assertThat(transferLogs.page(subject,task,null,null,null,null,1,1).get("total")).isEqualTo(2L);
        assertThat(transferLogs.page(subject,task,null,"ERROR",null,null,20,0).get("total")).isEqualTo(1L);
        assertThat(transferLogs.page("other",null,null,null,null,null,20,0).get("total")).isEqualTo(0L);
        assertThatThrownBy(()->transferLogs.page("other",task,null,null,null,null,20,0)).isInstanceOf(ReceiverFailure.class);
        String path="/api/v1/receiving-tasks/transfer-logs";
        assertThat(http.getForEntity(path,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var h=new HttpHeaders();h.set("X-Test-Login","test-login");h.set("X-Test-Subject","other");
        assertThat(http.exchange(path+"?taskId="+task,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getBody().path("data").path("total").asInt()).isZero();
        h.set("X-Test-Subject",subject);
        for(String query:List.of("limit=0","limit=51","offset=-1","eventType=SQL","outcome=FAKE","from=bad","from=2026-01-02T00:00:00Z&until=2026-01-01T00:00:00Z"))assertThat(http.exchange(path+"?"+query,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void startupDoesNotInventPastTransfersButRecordsRecovery(){
        assertThat(transferLogs.page(subject,task,null,null,null,null,20,0).get("total")).isEqualTo(0L);
        inbox.recover();inbox.recover();
        assertThat(transferLogs.page(subject,task,"STARTING",null,null,null,20,0).get("total")).isEqualTo(1L);
        assertThat(transferLogs.page(subject,task,"BATCH_STORED",null,null,null,20,0).get("total")).isEqualTo(0L);
    }

    @Test void monitorQueueJoinsLogAndMessageTransactionAndSurvivesRetry(){
        var batch=UUID.randomUUID().toString();
        new TransactionTemplate(manager).executeWithoutResult(status->{inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now,batch);status.setRollbackOnly();});
        assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",Long.class,task)).isZero();
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now,batch);
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("DEMAND_TELEMETRY_TOKEN","x".repeat(43));var reporter=new MonitorReporter(db,json,foundation,environment);
        when(foundation.monitor(anyString(),anyString())).thenThrow(new ReceiverFailure(503,"offline"));reporter.flush();
        assertThat(db.queryForObject("SELECT q.report_state FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task)).isEqualTo("PENDING");
        String event=db.queryForObject("SELECT q.event_id FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task);
        doAnswer(call->{var body=json.readTree((String)call.getArgument(1));assertThat(body.toString()).doesNotContain(password,token,"message_value","encrypted_secrets","subject_id");assertThat(body.path("events").get(0).path("event_id").asText()).isEqualTo(event);return json.valueToTree(Map.of("event_ids",List.of(event),"accepted",1,"duplicates",0));}).when(foundation).monitor(anyString(),anyString());
        db.update("UPDATE receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id SET q.next_attempt_at=UTC_TIMESTAMP(3) WHERE l.task_id=?",task);reporter.flush();reporter.flush();
        assertThat(db.queryForObject("SELECT q.report_state FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task)).isEqualTo("SENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_transfer_log WHERE task_id=?",Long.class,task)).isEqualTo(1);assertThat(service.get(task,subject).get("received_count")).isEqualTo(1L);
    }
    @Test void monitorPermanentRejectionIsQuarantinedWithoutDeletingLogOrMessage(){
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        var reporter=new MonitorReporter(db,json,foundation,new org.springframework.mock.env.MockEnvironment().withProperty("DEMAND_TELEMETRY_TOKEN","x".repeat(43)));
        when(foundation.monitor(anyString(),anyString())).thenThrow(new ReceiverFailure(409,"conflict"));reporter.flush();
        assertThat(db.queryForObject("SELECT q.report_state FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task)).isEqualTo("QUARANTINED");assertThat(service.get(task,subject).get("received_count")).isEqualTo(1L);
        var disabled=new MonitorReporter(db,json,foundation,new org.springframework.mock.env.MockEnvironment());clearInvocations(foundation);disabled.flush();verifyNoInteractions(foundation);
    }
    @Test void migrationBackfillQueuesRealOldLogsWithoutInventingEvents(){
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        db.update("DELETE q FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",task);
        db.update("INSERT INTO receiving_monitor_outbox(log_id,event_id) SELECT log_id,UUID() FROM receiving_transfer_log WHERE task_id=?",task);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",Long.class,task)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_transfer_log WHERE task_id=?",Long.class,task)).isEqualTo(1L);
    }

    @Test void actualHttpDisconnectAndReporterRestartReplaySamePersistentEvent()throws Exception{
        inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now);
        int port;try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("DEMAND_TELEMETRY_TOKEN","x".repeat(43)).withProperty("DEMAND_FOUNDATION_URL","http://127.0.0.1:"+port);
        new MonitorReporter(db,json,new FoundationClient(environment,json),environment).flush();
        String event=db.queryForObject("SELECT q.event_id FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task);
        assertThat(db.queryForObject("SELECT q.report_state FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task)).isEqualTo("PENDING");
        var observed=new java.util.concurrent.atomic.AtomicReference<String>();var header=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/v1/telemetry/receiver-reports",exchange->{try{var body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);observed.set(body);header.set(exchange.getRequestHeaders().getFirst("X-Receiver-Telemetry-Token"));var id=json.readTree(body).path("events").get(0).path("event_id").asText();byte[] reply=json.writeValueAsBytes(Map.of("code",0,"data",Map.of("event_ids",List.of(id),"accepted",0,"duplicates",1)));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);}finally{exchange.close();}});
        server.start();try{
            db.update("UPDATE receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id SET q.next_attempt_at=UTC_TIMESTAMP(3) WHERE l.task_id=?",task);
            new MonitorReporter(db,json,new FoundationClient(environment,json),environment).flush();
            assertThat(db.queryForObject("SELECT q.report_state FROM receiving_monitor_outbox q JOIN receiving_transfer_log l ON l.log_id=q.log_id WHERE l.task_id=?",String.class,task)).isEqualTo("SENT");
            assertThat(observed.get()).contains(event).doesNotContain(password,token,"message_value","subject_id");assertThat(header.get()).isEqualTo("x".repeat(43));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM receiving_transfer_log WHERE task_id=?",Long.class,task)).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void measuredStorageDurationIsTechnicalAndDoesNotAlterCheckpoint(){
        String batch=UUID.randomUUID().toString();inbox.save(task,"cluster:topic",List.of(record(0,new byte[]{1})),now,now,batch);
        inbox.offsetResult(task,batch,Map.of(0,1L),false,31L);
        assertThat(db.queryForObject("SELECT storage_duration_ms FROM receiving_transfer_log WHERE task_id=? AND event_type='OFFSET_UNCONFIRMED'",Long.class,task)).isEqualTo(31);
        assertThat(db.queryForObject("SELECT next_offset FROM receiving_checkpoint WHERE task_id=?",Long.class,task)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM received_message WHERE task_id=?",Long.class,task)).isEqualTo(1);
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("DEMAND_TELEMETRY_TOKEN","x".repeat(43));var reporter=new MonitorReporter(db,json,foundation,env);
        doAnswer(call->{var e=json.readTree((String)call.getArgument(1)).path("events").get(0);if("OFFSET_UNCONFIRMED".equals(e.path("event_type").asText()))assertThat(e.path("storage_duration_ms").asInt()).isEqualTo(31);else assertThat(e.has("storage_duration_ms")).isFalse();return json.valueToTree(Map.of("event_ids",List.of(e.path("event_id").asText()),"accepted",1,"duplicates",0));}).when(foundation).monitor(anyString(),anyString());reporter.flush();
    }

}
