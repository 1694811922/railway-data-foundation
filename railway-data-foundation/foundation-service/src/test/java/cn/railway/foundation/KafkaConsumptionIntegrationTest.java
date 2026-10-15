package cn.railway.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KAFKA_SUBSCRIPTION_EXECUTION_ENABLED=true","KAFKA_SUBSCRIPTION_RECONCILE_MS=3600000"})
class KafkaConsumptionIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @org.springframework.test.context.bean.override.mockito.MockitoBean cn.railway.foundation.subscription.SubscriptionBroker receiverBroker;
    String tag,source,scan,resource,capability,provider,demand;
    final List<String> grants=new ArrayList<>();
    String id(String p) {return p+"_"+UUID.randomUUID().toString().replace("-","");}
    @BeforeEach void fixture() {
        org.mockito.Mockito.when(receiverBroker.enabled()).thenReturn(true);
        org.mockito.Mockito.when(receiverBroker.configuredCluster()).thenReturn("isolated_fixture_cluster");
        org.mockito.Mockito.when(receiverBroker.bootstrap()).thenReturn("127.0.0.1:19093");
        org.mockito.Mockito.when(receiverBroker.protocol()).thenReturn("SASL_PLAINTEXT");
        tag=id("TEST");source=id("SRC");scan=id("SCAN");resource=id("RES");capability=id("CAP");provider=id("SUB");demand=id("SUB");
        for(var entry:Map.of(provider,"PROVIDER",demand,"DEMAND").entrySet())
            db.update("INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,status,version,created_at,updated_at) VALUES(?,?,?,?,'测试单位','ENABLED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",entry.getKey(),entry.getKey(),tag+entry.getValue(),entry.getValue());
        db.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,description,status,created_at,updated_at) VALUES(?,?,'MYSQL','TEST_REF','','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",source,tag);
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES(?,'test_schema','container_event','',1)",scan);
        db.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,business_description,owner_department,security_level,update_mode,status,created_at,updated_at) VALUES(?,?,?,'test_schema','container_event',?,'测试资源','测试单位','内部','实时','PUBLISHED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",resource,source,scan,tag);
        db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,ordinal_position,time_field,business_name) VALUES(?,'event_time','datetime',false,1,true,'业务事件时间')",resource);
        db.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,topic,protocol,auth_type,status,health_status,created_at,updated_at) VALUES(?,?,?,'KAFKA_TOPIC',?,'KAFKA','SASL','ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",capability,resource,tag,tag);
    }
    @AfterEach void cleanup() {
        for(String grant:grants) {db.update("DELETE FROM kafka_consumption_report WHERE grant_id=?",grant);db.update("DELETE FROM kafka_subscription_execution WHERE grant_id=?",grant);db.update("DELETE FROM kafka_subscription_event WHERE grant_id=?",grant);db.update("DELETE FROM kafka_subscription_grant WHERE grant_id=?",grant);}
        db.update("DELETE FROM capability WHERE capability_id=?",capability);db.update("DELETE FROM resource_field WHERE resource_id=?",resource);db.update("DELETE FROM data_resource WHERE resource_id=?",resource);
        db.update("DELETE FROM metadata_scan_table WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_task WHERE task_id=?",scan);db.update("DELETE FROM source_system WHERE source_id=?",source);
        db.update("DELETE FROM sharing_subject WHERE subject_id IN (?,?)",provider,demand);
    }
    Map<String,Object> body() {
        var b=new HashMap<String,Object>();b.put("grant_name",tag);b.put("provider_subject_id",provider);b.put("demand_subject_id",demand);b.put("resource_id",resource);b.put("capability_id",capability);b.put("purpose","科研整主题订阅");
        b.put("valid_from",Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());b.put("valid_until",Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS).toString());b.put("whole_topic_acknowledged",true);b.put("reason","明确接受完整消息及历史");return b;
    }
    JsonNode create() {
        var response=http.postForEntity("/api/v1/kafka-subscriptions",body(),JsonNode.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var row=response.getBody().path("data");grants.add(row.path("grant_id").asText());return row;
    }
    ResponseEntity<JsonNode> action(JsonNode row,String action,boolean ack) {
        return http.postForEntity("/api/v1/kafka-subscriptions/"+row.path("grant_id").asText()+"/"+action,Map.of("version",row.path("version").asInt(),"reason","测试"+action,"whole_topic_acknowledged",ack),JsonNode.class);
    }
    JsonNode step(JsonNode row,String action,String target) {var response=action(row,action,true);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);var result=response.getBody().path("data");assertThat(result.path("status").asText()).isEqualTo(target);return result;}
    String token,activeId;
    JsonNode active(){
        var row=step(step(create(),"submit","PENDING"),"confirm","APPROVED");activeId=row.path("grant_id").asText();token="rkc_"+Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest;try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}
        db.update("INSERT INTO kafka_subscription_execution(grant_id,broker_username,cluster_id,desired_state,execution_state,issued_at,consumption_token_digest,updated_at) VALUES(?,?,'isolated_fixture_cluster','GRANTED','ACTIVE',UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3))",activeId,"rdfk_"+UUID.randomUUID().toString().replace("-",""),digest);
        return row;
    }
    Map<String,Object> report(){var now=Instant.now().truncatedTo(ChronoUnit.MILLIS);return new HashMap<>(Map.of("report_id",UUID.randomUUID().toString(),"started_at",now.minusMillis(250).toString(),"completed_at",now.toString(),"positions",List.of(Map.of("partition",0,"offset",7,"byte_count",320),Map.of("partition",0,"offset",8,"byte_count",0))));}
    ResponseEntity<JsonNode> send(String id,Object body,String credential){var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(credential!=null)headers.setBearerAuth(credential);return http.exchange("/api/v1/kafka-subscriptions/"+id+"/consumption-reports",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);}
    @Test void summariesUseObservedCountsAndCanonicalReplaysWithoutMutatingGovernance(){
        active();var before=db.queryForMap("SELECT * FROM kafka_subscription_grant WHERE grant_id=?",activeId);var resourceBefore=db.queryForMap("SELECT * FROM data_resource WHERE resource_id=?",resource);
        var empty=http.getForObject("/api/v1/kafka-subscriptions/"+activeId+"/consumption",JsonNode.class).path("data");assertThat(empty.path("summary").path("state").asText()).isEqualTo("NOT_REPORTED");assertThat(empty.path("summary").path("records").isNull()).isTrue();
        var body=report();var first=send(activeId,body,token);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(first.getBody().path("data").path("records").asInt()).isEqualTo(2);
        body.put("positions",List.of(Map.of("partition",0,"offset",8,"byte_count",0),Map.of("partition",0,"offset",7,"byte_count",320)));
        var replay=send(activeId,body,token);assertThat(replay.getBody().path("data").path("replayed").asBoolean()).isTrue();assertThat(replay.getBody().path("data").path("received_at")).isEqualTo(first.getBody().path("data").path("received_at"));
        var page=http.getForObject("/api/v1/kafka-subscriptions/"+activeId+"/consumption?limit=1",JsonNode.class).path("data");assertThat(page.path("total").asInt()).isEqualTo(1);assertThat(page.path("summary").path("records").asInt()).isEqualTo(2);assertThat(page.path("summary").path("bytes").asLong()).isEqualTo(320);
        assertThat(page.toString()).doesNotContain(token,"digest","password","positions_json");assertThat(db.queryForMap("SELECT * FROM kafka_subscription_grant WHERE grant_id=?",activeId)).isEqualTo(before);assertThat(db.queryForMap("SELECT * FROM data_resource WHERE resource_id=?",resource)).isEqualTo(resourceBefore);
        var content=db.queryForObject("SELECT positions_json FROM kafka_consumption_report WHERE grant_id=?",String.class,activeId);assertThat(content).doesNotContain("value","key","header",token);
        body.put("positions",List.of(Map.of("partition",0,"offset",9,"byte_count",1)));assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_consumption_report WHERE grant_id=?",Long.class,activeId)).isEqualTo(1);
    }
    @Test void reportCredentialsAreIndependentAndStopRejectsEvenCookieAuthenticatedReports(){
        active();var body=report();for(String bad:List.of("ks_"+"a".repeat(43),"rdf_"+"a".repeat(43),"rkc_"+"b".repeat(43)))assertThat(send(activeId,body,bad).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(activeId,body,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(send(id("KSG"),body,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED' WHERE grant_id=?",activeId);assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        db.update("UPDATE kafka_subscription_execution SET desired_state='GRANTED' WHERE grant_id=?",activeId);db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_consumption_report WHERE grant_id=?",Long.class,activeId)).isZero();
    }
    @Test void anotherGrantAndPreviousIdentityCannotReportAndExpiryRejectsNewObservations(){
        active();String firstId=activeId,firstToken=token;active();
        assertThat(send(activeId,report(),firstToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(firstId,report(),token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE kafka_subscription_execution SET consumption_token_digest=NULL WHERE grant_id=?",firstId);
        assertThat(send(firstId,report(),firstToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE kafka_subscription_grant SET valid_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE grant_id=?",activeId);
        assertThat(send(activeId,report(),token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_consumption_report WHERE grant_id IN (?,?)",Long.class,firstId,activeId)).isZero();
    }
    @Test void strictPayloadRejectsBusinessValuesUnknownIdentityInvalidTimesAndNumbers(){
        active();
        for(String extra:List.of("value","topic","consumer_group","subject_id","broker_username")){var body=report();body.put(extra,"must-not-be-stored");assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
        for(Object n:List.of("0",0.5,-1)){var body=report();body.put("positions",List.of(Map.of("partition",n,"offset",1,"byte_count",1)));assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
        var duplicate=report();duplicate.put("positions",List.of(Map.of("partition",0,"offset",1,"byte_count",1),Map.of("partition",0,"offset",1,"byte_count",1)));assertThat(send(activeId,duplicate,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var future=report();future.put("completed_at",Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS).toString());assertThat(send(activeId,future,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var longBatch=report();longBatch.put("started_at",Instant.now().minusSeconds(61).truncatedTo(ChronoUnit.MILLIS).toString());assertThat(send(activeId,longBatch,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var oversize=report();oversize.put("positions",List.of(Map.of("partition",0,"offset",1,"byte_count",134217728),Map.of("partition",0,"offset",2,"byte_count",1)));assertThat(send(activeId,oversize,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_consumption_report WHERE grant_id=?",Long.class,activeId)).isZero();
    }
    @Test void receiverControlUsesScopedTechnicalCredentialAndDisclosesNoSecrets(){
        active();String path="/api/v1/kafka-subscriptions/"+activeId+"/receiver-access/check";
        var h=new HttpHeaders();h.setBearerAuth(token);
        var response=http.exchange(path,HttpMethod.POST,new HttpEntity<>(Map.of(),h),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("grant").path("grant_id").asText()).isEqualTo(activeId);
        assertThat(response.getBody().toString()).doesNotContain(token,"password","credential_ref","encrypted","username\":\"root");
        h.setBearerAuth("rkc_"+"z".repeat(43));assertThat(http.exchange(path,HttpMethod.POST,new HttpEntity<>(Map.of(),h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        h.setBearerAuth(token);db.update("UPDATE kafka_subscription_execution SET desired_state='REVOKED' WHERE grant_id=?",activeId);
        assertThat(http.exchange(path,HttpMethod.POST,new HttpEntity<>(Map.of(),h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void administratorCannotOperateDemandReceiverContextAndAnonymousCannotUseWorkspaceRoute(){
        active();String path="/api/v1/kafka-subscriptions/"+activeId+"/receiver-context";
        assertThat(http.postForEntity(path,Map.of(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var anonymous=new TestRestTemplate();assertThat(anonymous.postForEntity(http.getRootUri()+path,Map.of(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void concurrentIdenticalRetryIsCountedOnceAndNewReportMayCountRepeatedRead()throws Exception{
        active();var body=report();var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Future<ResponseEntity<JsonNode>>>();for(int i=0;i<4;i++)jobs.add(pool.submit(()->send(activeId,body,token)));for(var result:jobs)assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.OK);}finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_consumption_report WHERE grant_id=?",Long.class,activeId)).isEqualTo(1);
        body.put("report_id",UUID.randomUUID().toString());assertThat(send(activeId,body,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        var page=http.getForObject("/api/v1/kafka-subscriptions/"+activeId+"/consumption?limit=1&offset=1",JsonNode.class).path("data");assertThat(page.path("total").asInt()).isEqualTo(2);assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("summary").path("records").asLong()).isEqualTo(4);
    }
}
