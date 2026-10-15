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
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Dedicated Foundation test database. Fixture control state does not prove Provider authorization. */
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelationshipIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    String source,scan,resource,capability,provider,demand,tag;
    final List<String> relationships=new ArrayList<>();
    final List<String> policies=new ArrayList<>();
    String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    @BeforeEach void fixture() {
        tag=id("TEST").toUpperCase(Locale.ROOT);source=id("SRC");scan=id("SCAN");resource=id("RES");capability=id("CAP");
        provider=subject("PROVIDER");demand=subject("DEMAND");
        db.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,description,status,created_at,updated_at) VALUES(?,?,'MYSQL','TEST_REF','','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",source,tag);
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES(?,'test_schema','container_event','',3)",scan);
        db.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,business_description,owner_department,security_level,update_mode,status,created_at,updated_at) VALUES(?,?,?,'test_schema','container_event',?,'测试资源','测试单位','内部','实时','PUBLISHED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",resource,source,scan,tag);
        for(var f:List.of("container_no","event_time","secret_field"))db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,ordinal_position,time_field) VALUES(?,?,?,false,?,?)",resource,f,f.equals("event_time")?"datetime(3)":"varchar(20)",f.equals("event_time")?2:1,f.equals("event_time"));
        db.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,http_method,endpoint,protocol,auth_type,status,health_status,created_at,updated_at) VALUES(?,?,?,'REST_API','GET','/containers/events','HTTP','NONE','ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",capability,resource,tag);
    }
    String subject(String type) {
        var r=http.postForEntity("/api/v1/subjects",Map.of("subject_code",tag+"_"+type,"subject_name",tag+type,"subject_type",type,"owner_department","测试单位"),JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);return r.getBody().path("data").path("subject_id").asText();
    }
    @AfterEach void cleanup() {
        for(String id:relationships){db.update("DELETE FROM sharing_relationship_event WHERE relationship_id=?",id);db.update("DELETE FROM sharing_relationship WHERE relationship_id=?",id);}
        for(String id:policies)db.update("DELETE FROM access_policy WHERE policy_id=?",id);
        db.update("DELETE FROM capability WHERE resource_id=?",resource);db.update("DELETE FROM resource_field WHERE resource_id=?",resource);
        db.update("DELETE FROM data_resource WHERE resource_id=?",resource);db.update("DELETE FROM metadata_scan_table WHERE task_id=?",scan);
        db.update("DELETE FROM metadata_scan_task WHERE task_id=?",scan);db.update("DELETE FROM source_system WHERE source_id=?",source);
        db.update("DELETE FROM sharing_subject WHERE subject_id IN (?,?)",provider,demand);
    }
    Map<String,Object> policy(){return new HashMap<>(Map.of("allowed_fields",List.of("container_no","event_time"),"time_field","event_time","data_from","2026-01-01T00:00:00Z","data_until","2027-01-01T00:00:00Z"));}
    Map<String,Object> body(){return new HashMap<>(Map.of("relationship_name",tag,"provider_subject_id",provider,"demand_subject_id",demand,"resource_id",resource,"capability_id",capability,"purpose","铁路科研分析","valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"valid_until",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"policy",policy(),"reason","登记测试关系"));}
    JsonNode create() {
        var r=http.postForEntity("/api/v1/relationships",body(),JsonNode.class);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var row=r.getBody().path("data");relationships.add(row.path("relationship_id").asText());policies.add(row.path("policy").path("policy_id").asText());return row;
    }
    ResponseEntity<JsonNode> action(String id,String action,int version){return http.postForEntity("/api/v1/relationships/"+id+"/"+action,Map.of("version",version,"reason","测试"+action),JsonNode.class);}
    JsonNode transition(JsonNode row,String action,String target){var r=action(row.path("relationship_id").asText(),action,row.path("version").asInt());assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(r.getBody().path("data").path("status").asText()).isEqualTo(target);return r.getBody().path("data");}
    @Test void workflowRequiresConfirmationAndPreservesHistory() {
        var row=create();String id=row.path("relationship_id").asText();assertThat(row.path("status").asText()).isEqualTo("DRAFT");
        assertThat(action(id,"enable",1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        row=transition(row,"submit","PENDING");assertThat(action(id,"enable",2).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        row=transition(row,"confirm","CONFIRMED");assertThat(row.path("confirmed_at").isNull()).isFalse();
        row=transition(row,"enable","ACTIVE");row=transition(row,"suspend","SUSPENDED");row=transition(row,"enable","ACTIVE");row=transition(row,"revoke","REVOKED");
        assertThat(action(id,"enable",row.path("version").asInt()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var events=http.getForObject("/api/v1/relationships/"+id+"/events",JsonNode.class).path("data");assertThat(events.size()).isEqualTo(7);
        assertThat(events.get(6).path("version").asInt()).isEqualTo(7);assertThat(events.get(6).path("operator_name").asText()).isEqualTo(testAdminUsername);
        assertThat(row.path("policy").path("allowed_fields").toString()).doesNotContain("secret_field");
        assertThat(http.getForObject("/api/v1/access-policies/"+policies.get(0),JsonNode.class).path("data")).isEqualTo(row.path("policy"));
    }
    @Test void roleOwnershipSourceAndFieldsAreValidatedWithoutPartialWrites() {
        int before=db.queryForObject("SELECT COUNT(*) FROM access_policy",Integer.class);
        for(String key:List.of("provider_subject_id","demand_subject_id","capability_id","resource_id")){
            var b=body();b.put(key,key.equals("provider_subject_id")?demand:key.equals("demand_subject_id")?provider:"missing");
            assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for(var fields:List.of(List.of("*"),List.of("missing"),List.of("event_time","event_time"),List.<String>of())){
            var p=policy();p.put("allowed_fields",fields);var b=body();b.put("policy",p);
            assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for(String field:List.of("container_no","secret_field","missing")){
            var p=policy();p.put("time_field",field);var b=body();b.put("policy",p);assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        db.update("UPDATE source_system SET status='DISABLED' WHERE source_id=?",source);assertThat(http.postForEntity("/api/v1/relationships",body(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE source_system SET status='ENABLED' WHERE source_id=?",source);
        db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,ordinal_position,time_field) VALUES(?,'*','varchar(20)',false,4,false)",resource);
        var wildcard=policy();wildcard.put("allowed_fields",List.of("*"));var request=body();request.put("policy",wildcard);
        assertThat(http.postForEntity("/api/v1/relationships",request,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM access_policy",Integer.class)).isEqualTo(before);
    }
    @Test void timeRangesAndUnknownPropertiesCannotBypassChecks() {
        for(var patch:List.of(Map.of("valid_until","2025-01-01T00:00:00Z"),Map.of("valid_from","2030-01-01T00:00:00Z"),Map.of("operator_name","伪造管理员"),Map.of("status","ACTIVE"))){
            var b=body();b.putAll(patch);assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var p=policy();p.put("data_until","2025-01-01T00:00:00Z");var b=body();b.put("policy",p);assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        b=body();b.put("valid_until","2027-01-01T00:00:00.000001Z");assertThat(http.postForEntity("/api/v1/relationships",b,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var row=create();assertThat(http.postForEntity("/api/v1/relationships/"+relationships.get(0)+"/submit",Map.of("version",1,"reason","  "),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(row.path("version").asInt()).isEqualTo(1);
    }
    @Test void draftEditsAndReturnedChangesRequireFreshConfirmation() {
        var row=create();String id=relationships.get(0);row=transition(row,"submit","PENDING");row=transition(row,"return","DRAFT");
        var request=new HashMap<String,Object>();request.put("relationship_name","调整共享");request.put("purpose","新用途");request.put("valid_from",body().get("valid_from"));request.put("valid_until",body().get("valid_until"));request.put("policy",policy());request.put("version",3);request.put("reason","调整策略");
        var result=http.exchange("/api/v1/relationships/"+id,HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        row=result.getBody().path("data");assertThat(row.path("version").asInt()).isEqualTo(4);assertThat(row.path("policy").path("version").asInt()).isEqualTo(2);assertThat(row.path("confirmed_at").isNull()).isTrue();
        assertThat(http.exchange("/api/v1/relationships/"+id,HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        row=transition(row,"submit","PENDING");row=transition(row,"confirm","CONFIRMED");request.put("version",6);
        assertThat(http.exchange("/api/v1/relationships/"+id,HttpMethod.PUT,new HttpEntity<>(request),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void pausedRevocationRemainsPossibleWhenReferencesBecomeUnavailable() {
        var row=transition(transition(create(),"submit","PENDING"),"confirm","CONFIRMED");
        db.update("UPDATE capability SET status='DISABLED' WHERE capability_id=?",capability);assertThat(action(relationships.get(0),"enable",3).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET status='ENABLED' WHERE capability_id=?",capability);row=transition(row,"enable","ACTIVE");
        db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);row=transition(row,"suspend","SUSPENDED");
        assertThat(action(relationships.get(0),"enable",row.path("version").asInt()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        row=transition(row,"revoke","REVOKED");assertThat(row.path("status").asText()).isEqualTo("REVOKED");
        assertThat(http.exchange("/api/v1/subjects/"+demand,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void expiryAndFutureStartPreventActivationAndCapabilityDeletionIsGuarded() {
        var row=transition(transition(create(),"submit","PENDING"),"confirm","CONFIRMED");
        db.update("UPDATE sharing_relationship SET valid_from='2024-01-01',valid_until='2025-01-01' WHERE relationship_id=?",relationships.get(0));
        assertThat(action(relationships.get(0),"enable",3).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE sharing_relationship SET valid_from='2098-01-01',valid_until='2099-01-01' WHERE relationship_id=?",relationships.get(0));
        assertThat(action(relationships.get(0),"enable",3).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE capability SET status='DRAFT' WHERE capability_id=?",capability);
        assertThat(http.exchange("/api/v1/capabilities/"+capability,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM capability WHERE capability_id=?",Integer.class,capability)).isEqualTo(1);
    }
    @Test void concurrentStatusChangesProduceOneEventAndOneWinner() throws Exception {
        create();String id=relationships.get(0);var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{gate.await();return action(id,"submit",1).getStatusCode();});var b=executor.submit(()->{gate.await();return action(id,"revoke",1).getStatusCode();});
            gate.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM sharing_relationship_event WHERE relationship_id=?",Integer.class,id)).isEqualTo(2);
        }finally{executor.shutdownNow();}
    }
    @Test void paginationMissingRecordsAndUnsupportedTypesAreExplicit() {
        var row=create();String id=relationships.get(0);
        assertThat(http.getForObject("/api/v1/relationships?limit=1",JsonNode.class).path("data").size()).isEqualTo(1);
        assertThat(http.getForObject("/api/v1/access-policies?limit=1",JsonNode.class).path("data").size()).isEqualTo(1);
        assertThat(http.getForEntity("/api/v1/relationships?limit=201",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/relationships/missing",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("UPDATE capability SET type='KAFKA_TOPIC' WHERE capability_id=?",capability);
        row=transition(row,"submit","PENDING");row=transition(row,"confirm","CONFIRMED");assertThat(action(id,"enable",3).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
