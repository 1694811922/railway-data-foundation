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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaSubscriptionIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    String tag,source,scan,resource,capability,provider,demand;
    final List<String> grants=new ArrayList<>();
    String id(String p) {return p+"_"+UUID.randomUUID().toString().replace("-","");}
    @BeforeEach void fixture() {
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
        for(String grant:grants) {db.update("DELETE FROM kafka_subscription_event WHERE grant_id=?",grant);db.update("DELETE FROM kafka_subscription_grant WHERE grant_id=?",grant);}
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
    @Test void independentApprovalAndRevocationNeverClaimBrokerAccess() {
        var beforeResource=db.queryForMap("SELECT * FROM data_resource WHERE resource_id=?",resource);var beforeField=db.queryForMap("SELECT * FROM resource_field WHERE resource_id=?",resource);var beforeCap=db.queryForMap("SELECT * FROM capability WHERE capability_id=?",capability);
        long policies=db.queryForObject("SELECT COUNT(*) FROM access_policy",Long.class),relationships=db.queryForObject("SELECT COUNT(*) FROM sharing_relationship",Long.class);
        var row=create();assertThat(action(row,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        row=step(row,"submit","PENDING");assertThat(action(row,"confirm",false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);row=step(row,"confirm","APPROVED");
        assertThat(row.path("scope").asText()).isEqualTo("FULL_TOPIC");assertThat(row.path("confirmed_at").isNull()).isFalse();assertThat(row.path("execution_state").asText()).isEqualTo("NOT_PROVISIONED");assertThat(row.path("broker_access_confirmed").asBoolean()).isFalse();assertThat(row.toString()).doesNotContain("password","encrypted","allowed_fields");
        assertThat(http.postForEntity("/api/v1/kafka-subscriptions/"+grants.get(0)+"/provision",Map.of("version",row.path("version").asInt(),"reason","未配置Broker不得伪装成功"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_execution WHERE grant_id=?",Integer.class,grants.get(0))).isZero();
        row=step(row,"revoke","REVOKED");assertThat(action(row,"submit",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var events=http.getForObject("/api/v1/kafka-subscriptions/"+grants.get(0)+"/events",JsonNode.class).path("data");assertThat(events.size()).isEqualTo(4);assertThat(events.get(0).path("whole_topic_acknowledged").asBoolean()).isTrue();assertThat(events.get(2).path("whole_topic_acknowledged").asBoolean()).isTrue();
        assertThat(db.queryForMap("SELECT * FROM data_resource WHERE resource_id=?",resource)).isEqualTo(beforeResource);assertThat(db.queryForMap("SELECT * FROM resource_field WHERE resource_id=?",resource)).isEqualTo(beforeField);assertThat(db.queryForMap("SELECT * FROM capability WHERE capability_id=?",capability)).isEqualTo(beforeCap);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM access_policy",Long.class)).isEqualTo(policies);assertThat(db.queryForObject("SELECT COUNT(*) FROM sharing_relationship",Long.class)).isEqualTo(relationships);
    }
    @Test void scopeAndBindingCannotBeForgedOrReuseRestPolicy() {
        long before=db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_grant",Long.class);
        for(var patch:List.of(Map.of("whole_topic_acknowledged",false),Map.of("status","APPROVED"),Map.of("execution_state","ACTIVE"),Map.of("scope","FIELD_FILTERED"),Map.of("policy",Map.of("allowed_fields",List.of("event_time"))),Map.of("consumer_group","*"),Map.of("operator_name","伪造"),Map.of("provider_subject_id",demand),Map.of("capability_id","missing"))) {
            var b=body();b.putAll(patch);assertThat(http.postForEntity("/api/v1/kafka-subscriptions",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var b=body();b.remove("whole_topic_acknowledged");assertThat(http.postForEntity("/api/v1/kafka-subscriptions",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET type='REST_API' WHERE capability_id=?",capability);assertThat(http.postForEntity("/api/v1/kafka-subscriptions",body(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET type='KAFKA_TOPIC',auth_type='NONE' WHERE capability_id=?",capability);assertThat(http.postForEntity("/api/v1/kafka-subscriptions",body(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET auth_type='SASL',topic='*' WHERE capability_id=?",capability);assertThat(http.postForEntity("/api/v1/kafka-subscriptions",body(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_grant",Long.class)).isEqualTo(before);
    }
    @Test void stoppedDependenciesPreventApprovalButNotReturnOrRevocation() {
        var row=step(create(),"submit","PENDING");
        db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);assertThat(action(row,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);row=step(row,"return","DRAFT");row=step(row,"revoke","REVOKED");
        db.update("UPDATE sharing_subject SET status='ENABLED' WHERE subject_id=?",demand);var other=step(create(),"submit","PENDING");
        db.update("UPDATE source_system SET status='DISABLED' WHERE source_id=?",source);assertThat(action(other,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE source_system SET status='ENABLED' WHERE source_id=?",source);db.update("UPDATE capability SET status='DISABLED' WHERE capability_id=?",capability);assertThat(action(other,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);step(other,"revoke","REVOKED");
    }
    @Test void changedTopicOrHealthCannotBeConfirmed() {
        var row=step(create(),"submit","PENDING");db.update("UPDATE capability SET topic='changed_topic' WHERE capability_id=?",capability);assertThat(action(row,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET topic=?,health_status='DOWN' WHERE capability_id=?",tag,capability);assertThat(action(row,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);step(row,"revoke","REVOKED");
    }
    @Test void editsRequireCurrentDraftVersionAndKeepBindingGroupScope() {
        var row=create();var request=new HashMap<String,Object>();request.put("version",1);request.put("grant_name","调整申请");request.put("purpose","调整用途");request.put("valid_from",body().get("valid_from"));request.put("valid_until",body().get("valid_until"));request.put("reason","编辑原因");
        var result=http.exchange("/api/v1/kafka-subscriptions/"+grants.get(0),HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);var updated=result.getBody().path("data");assertThat(updated.path("consumer_group")).isEqualTo(row.path("consumer_group"));assertThat(updated.path("topic")).isEqualTo(row.path("topic"));
        assertThat(http.exchange("/api/v1/kafka-subscriptions/"+grants.get(0),HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        updated=step(updated,"submit","PENDING");updated=step(updated,"confirm","APPROVED");request.put("version",updated.path("version").asInt());assertThat(http.exchange("/api/v1/kafka-subscriptions/"+grants.get(0),HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void expiryAndInvalidTimePrecisionAreRejected() {
        for(var patch:List.of(Map.of("valid_until","2025-01-01T00:00:00Z"),Map.of("valid_from","2099-01-01T00:00:00Z"),Map.of("valid_until","2099-01-01T00:00:00.000001Z"))) {var b=body();b.putAll(patch);assertThat(http.postForEntity("/api/v1/kafka-subscriptions",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
        var row=step(create(),"submit","PENDING");db.update("UPDATE kafka_subscription_grant SET valid_from='2024-01-01',valid_until='2025-01-01' WHERE grant_id=?",grants.get(0));assertThat(action(row,"confirm",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);step(row,"revoke","REVOKED");
    }
    @Test void referencedSubjectsCapabilitiesAndImmutableTopicsAreProtected() {
        create();db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);assertThat(http.exchange("/api/v1/subjects/"+demand,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET status='DRAFT' WHERE capability_id=?",capability);assertThat(http.exchange("/api/v1/capabilities/"+capability,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var cap=Map.of("resource_id",resource,"capability_name",tag,"type","KAFKA_TOPIC","topic","other.topic","protocol","KAFKA","auth_type","SASL");assertThat(http.exchange("/api/v1/capabilities/"+capability,HttpMethod.PUT,new HttpEntity<>(cap),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void paginationSearchUniqueGroupsAndUnknownObjectsAreExplicit() {
        var one=create();var two=create();assertThat(one.path("consumer_group")).isNotEqualTo(two.path("consumer_group"));
        var page=http.getForObject("/api/v1/kafka-subscriptions?limit=1&keyword="+tag,JsonNode.class).path("data");assertThat(page.path("total").asInt()).isEqualTo(2);assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(http.getForEntity("/api/v1/kafka-subscriptions?limit=201",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(http.getForEntity("/api/v1/kafka-subscriptions?status=ACTIVE",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(http.getForEntity("/api/v1/kafka-subscriptions/missing/events",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void concurrentOperationsLeaveOneWinnerOneHistoryEvent() throws Exception {
        var row=create();var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {var a=executor.submit(()->{gate.await();return action(row,"submit",true).getStatusCode();});var b=executor.submit(()->{gate.await();return action(row,"revoke",true).getStatusCode();});gate.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_event WHERE grant_id=?",Integer.class,grants.get(0))).isEqualTo(2);}
        finally {executor.shutdownNow();}
    }
    @Test void historyInsertFailureRollsBackState() {
        var row=create();db.update("INSERT INTO kafka_subscription_event(grant_id,action,from_status,to_status,whole_topic_acknowledged,operator_name,reason,version,occurred_at) VALUES(?,'SUBMIT','DRAFT','PENDING',false,'测试','故意制造唯一键冲突',2,UTC_TIMESTAMP(3))",grants.get(0));
        assertThat(action(row,"submit",true).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);var actual=http.getForObject("/api/v1/kafka-subscriptions/"+grants.get(0),JsonNode.class).path("data");assertThat(actual.path("status").asText()).isEqualTo("DRAFT");assertThat(actual.path("version").asInt()).isEqualTo(1);
    }
}
