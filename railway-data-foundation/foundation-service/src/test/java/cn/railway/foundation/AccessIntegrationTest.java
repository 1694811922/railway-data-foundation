package cn.railway.foundation;

import cn.railway.foundation.metadata.SourceConnectionFactory;
import cn.railway.foundation.source.SourceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Isolated Foundation test metadata. Provider data execution is covered separately and by live flow. */
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @MockitoSpyBean SourceConnectionFactory connections;
    static final String CONTROL="test_provider_"+"c".repeat(43);
    String source,scan,resource,capability,provider,demand,relationship,policy,token,credential,tag;
    final List<String> subjects=new ArrayList<>();
    static URI sourceUri(){return URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));}
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p){
        p.add("PROVIDER_CONTROL_TOKEN",()->CONTROL);p.add("PROVIDER_SOURCE_ENV_PREFIX",()->"SOURCE_ACCESS_TEST");
        p.add("SOURCE_ACCESS_TEST_HOST",()->sourceUri().getHost());p.add("SOURCE_ACCESS_TEST_PORT",()->sourceUri().getPort());
        p.add("SOURCE_ACCESS_TEST_DATABASE",()->sourceUri().getPath().substring(1));p.add("SOURCE_ACCESS_TEST_USER",()->System.getenv("TEST_PROVIDER_SOURCE_DB_USER"));
    }
    String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    JsonNode success(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(r.getBody().path("code").asInt()).isZero();return r.getBody().path("data");}
    String subject(String role){var row=success(http.postForEntity("/api/v1/subjects",Map.of("subject_code",tag+"_"+role+subjects.size(),"subject_name",tag+role,"subject_type",role,"owner_department","测试单位"),JsonNode.class));String id=row.path("subject_id").asText();subjects.add(id);return id;}
    @BeforeEach void fixture(){
        tag=id("ACCESS").toUpperCase(Locale.ROOT);source=id("SRC");scan=id("SCAN");resource=id("RES");capability=id("CAP");
        provider=subject("PROVIDER");demand=subject("DEMAND");String schema=sourceUri().getPath().substring(1);
        db.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,description,status,created_at,updated_at) VALUES(?,?,'MYSQL','SOURCE_ACCESS_TEST','','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",source,tag);
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES(?,?,'container_event','',3)",scan,schema);
        db.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,business_description,owner_department,security_level,update_mode,status,created_at,updated_at) VALUES(?,?,?,?,'container_event',?,'测试资源','测试单位','内部','实时','PUBLISHED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",resource,source,scan,schema,tag);
        for(String f:List.of("container_no","event_time","train_no"))db.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,ordinal_position,time_field) VALUES(?,?,?,false,?,?)",resource,f,f.equals("event_time")?"datetime(3)":"varchar(30)",f.equals("event_time")?2:1,f.equals("event_time"));
        db.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,http_method,endpoint,protocol,auth_type,status,health_status,created_at,updated_at) VALUES(?,?,?,'REST_API','GET','/containers/events','HTTP','NONE','ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",capability,resource,tag);
        var body=new LinkedHashMap<String,Object>();body.put("relationship_name",tag);body.put("provider_subject_id",provider);body.put("demand_subject_id",demand);body.put("resource_id",resource);body.put("capability_id",capability);body.put("purpose","科研");body.put("reason","测试登记");body.put("valid_from",millis(-60));body.put("valid_until",millis(3600));
        body.put("policy",Map.of("allowed_fields",List.of("container_no","event_time"),"time_field","event_time","data_from","2026-09-20T00:00:00Z","data_until","2026-09-21T00:00:00Z"));
        var relation=success(http.postForEntity("/api/v1/relationships",body,JsonNode.class));relationship=relation.path("relationship_id").asText();policy=relation.path("policy").path("policy_id").asText();
        var issued=success(issue(demand,"首次测试凭据",millis(3600)));token=issued.path("token").asText();credential=issued.path("credential").path("credential_id").asText();clearInvocations(connections);
    }
    static String millis(long seconds){return Instant.now().plusSeconds(seconds).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();}
    ResponseEntity<JsonNode> issue(String id,String name,String expires){return http.postForEntity("/api/v1/subjects/"+id+"/credentials",Map.of("credential_name",name,"expires_at",expires,"reason","测试签发"),JsonNode.class);}
    Map<String,Object> request(){return new HashMap<>(Map.of("relationship_id",relationship,"capability_id",capability));}
    ResponseEntity<JsonNode> check(String bearer,String control,Map<String,Object> request){var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.set("Authorization",bearer);if(control!=null)headers.set("X-Provider-Token",control);return http.postForEntity("/api/v1/access/check",new HttpEntity<>(request,headers),JsonNode.class);}
    JsonNode decision(Map<String,Object> body){return success(check("Bearer "+token,CONTROL,body));}
    void denied(Map<String,Object> body,String reason){var data=decision(body);assertThat(data.path("allowed").asBoolean()).isFalse();assertThat(data.path("reason_code").asText()).isEqualTo(reason);assertThat(data.path("allowed_fields").size()).isZero();}
    JsonNode action(String action,int version){return success(http.postForEntity("/api/v1/relationships/"+relationship+"/"+action,Map.of("version",version,"reason","测试"+action),JsonNode.class));}
    void active(){action("submit",1);action("confirm",2);action("enable",3);}
    @AfterEach void cleanup(){
        for(String id:subjects){db.update("DELETE e FROM caller_credential_event e JOIN caller_credential c ON c.credential_id=e.credential_id WHERE c.subject_id=?",id);db.update("DELETE FROM caller_credential WHERE subject_id=?",id);}
        if(relationship!=null){db.update("DELETE FROM sharing_relationship_event WHERE relationship_id=?",relationship);db.update("DELETE FROM sharing_relationship WHERE relationship_id=?",relationship);}
        if(policy!=null)db.update("DELETE FROM access_policy WHERE policy_id=?",policy);
        db.update("DELETE FROM capability WHERE resource_id=?",resource);db.update("DELETE FROM resource_field WHERE resource_id=?",resource);db.update("DELETE FROM data_resource WHERE resource_id=?",resource);db.update("DELETE FROM metadata_scan_table WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_task WHERE task_id=?",scan);db.update("DELETE FROM source_system WHERE source_id=?",source);
        for(String id:subjects)db.update("DELETE FROM sharing_subject WHERE subject_id=?",id);
    }
    @Test void tokensAreRandomWriteOnceAndNeverReturnedByReadInterfaces(){
        assertThat(token.matches("rdf_[A-Za-z0-9_-]{43}")).isTrue();var next=success(issue(demand,"第二个测试凭据",millis(3600))).path("token").asText();assertThat(next.equals(token)).isFalse();
        String digest=db.queryForObject("SELECT token_digest FROM caller_credential WHERE credential_id=?",String.class,credential);assertThat(digest.matches("[a-f0-9]{64}")).isTrue();assertThat(digest.equals(token)).isFalse();
        for(String path:List.of("/api/v1/caller-credentials/"+credential,"/api/v1/caller-credentials?subjectId="+demand,"/api/v1/caller-credentials/"+credential+"/events")){
            String view=http.getForObject(path,String.class);assertThat(view.contains(token)||view.contains(next)||view.contains(digest)||view.contains("token_digest")).isFalse();
        }
        assertThat(issue(demand,"第三个测试凭据",millis(3600)).getHeaders().getCacheControl()).contains("no-store");
    }
    @Test void credentialIssuanceRejectsInactiveRolesExpiredTimesAndClientControlledSecrets(){
        assertThat(issue(provider,"错误角色",millis(3600)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(issue(demand,"首次测试凭据",millis(3600)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(issue(demand,"过期",millis(-1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(issue(demand,"过高精度","2099-01-01T00:00:00.000001Z").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body=Map.of("credential_name","伪造","expires_at",millis(3600),"reason","测试","token","client-token");
        assertThat(http.postForEntity("/api/v1/subjects/"+demand+"/credentials",body,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);assertThat(issue(demand,"停用",millis(3600)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void trustedCredentialAndProviderAuthenticationCannotBeReplacedByReportedSubject()throws Exception{
        active();assertThat(check("Bearer "+token,null,request()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(check("Bearer "+token,"wrong",request()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String auth:List.of("Bearer "+demand,"Bearer rdf_"+"z".repeat(43),"Basic "+token))assertThat(success(check(auth,CONTROL,request())).path("reason_code").asText()).isEqualTo("IDENTITY_INVALID");
        assertThat(success(check(null,CONTROL,request())).path("allowed").asBoolean()).isFalse();
        String otherSubject=subject("DEMAND");String otherToken=success(issue(otherSubject,"其他需求方",millis(3600))).path("token").asText();var otherDenied=success(check("Bearer "+otherToken,CONTROL,request()));assertThat(otherDenied.path("reason_code").asText()).isEqualTo("RELATIONSHIP_UNAVAILABLE");
        assertThat(otherDenied.path("subject_id").asText()).isEqualTo(otherSubject);assertThat(otherDenied.path("resource_id").isNull()).isTrue();assertThat(otherDenied.path("policy_id").isNull()).isTrue();
        var body=request();body.put("subject_id",demand);assertThat(check("Bearer "+token,CONTROL,body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(connections,never()).openConnection(anyString());verify(connections,never()).openConnection(any(SourceEntity.class));
    }
    @Test void omittedFieldsUsePolicyAndExplicitFieldsFiltersAndTimesCannotExpandScope()throws Exception{
        active();var result=decision(request());assertThat(result.path("allowed").asBoolean()).isTrue();assertThat(result.path("allowed_fields").toString()).isEqualTo("[\"container_no\",\"event_time\"]");assertThat(result.path("data_from").asText()).isEqualTo("2026-09-20T00:00:00Z");
        for(var values:List.of(List.<String>of(),List.of("*"),List.of("train_no"),List.of("event_time","event_time"))){var body=request();body.put("requested_fields",values);denied(body,"FIELDS_FORBIDDEN");}
        var filter=request();filter.put("filter_fields",List.of("train_no"));denied(filter,"FIELDS_FORBIDDEN");
        for(var patch:List.of(Map.of("from","2026-09-19T23:59:59Z"),Map.of("to","2026-09-21T00:00:00.001Z"),Map.of("from","2026-09-21T00:00:00Z"))){var body=request();body.putAll(patch);denied(body,"TIME_FORBIDDEN");}
        var narrow=request();narrow.put("requested_fields",List.of("container_no"));narrow.put("filter_fields",List.of("container_no"));narrow.put("from","2026-09-20T08:10:00+08:00");narrow.put("to","2026-09-20T08:20:00+08:00");
        var selected=decision(narrow);assertThat(selected.path("allowed").asBoolean()).isTrue();assertThat(selected.path("allowed_fields").size()).isEqualTo(1);assertThat(selected.path("data_from").asText()).isEqualTo("2026-09-20T00:10:00Z");
        verify(connections,never()).openConnection(anyString());verify(connections,never()).openConnection(any(SourceEntity.class));
    }
    @Test void onlyConfirmedActiveRelationshipsAreAllowedAndPauseRevokeAffectNextCheck(){
        var rejected=decision(request());assertThat(rejected.path("subject_id").asText()).isEqualTo(demand);assertThat(rejected.path("resource_id").asText()).isEqualTo(resource);assertThat(rejected.path("relationship_version").asInt()).isEqualTo(1);assertThat(rejected.path("allowed_fields").isEmpty()).isTrue();
        denied(request(),"RELATIONSHIP_INACTIVE");action("submit",1);denied(request(),"RELATIONSHIP_INACTIVE");action("confirm",2);denied(request(),"RELATIONSHIP_INACTIVE");action("enable",3);assertThat(decision(request()).path("allowed").asBoolean()).isTrue();
        action("suspend",4);denied(request(),"RELATIONSHIP_INACTIVE");action("enable",5);assertThat(decision(request()).path("allowed").asBoolean()).isTrue();action("revoke",6);denied(request(),"RELATIONSHIP_INACTIVE");
    }
    @Test void statusAndPolicyChangesAreRecheckedWithoutAllowCache(){
        active();
        for(String table:List.of("source_system","data_resource","capability","sharing_subject")){
            String key=Map.of("source_system","source_id","data_resource","resource_id","capability","capability_id","sharing_subject","subject_id").get(table),id=Map.of("source_system",source,"data_resource",resource,"capability",capability,"sharing_subject",provider).get(table);
            db.update("UPDATE "+table+" SET status=? WHERE "+key+"=?",table.equals("data_resource")?"DRAFT":"DISABLED",id);assertThat(decision(request()).path("allowed").asBoolean()).isFalse();db.update("UPDATE "+table+" SET status=? WHERE "+key+"=?",table.equals("data_resource")?"PUBLISHED":"ENABLED",id);assertThat(decision(request()).path("allowed").asBoolean()).isTrue();
        }
        db.update("UPDATE resource_field SET time_field=false WHERE resource_id=? AND field_name='event_time'",resource);denied(request(),"POLICY_UNAVAILABLE");
    }
    @Test void credentialRevocationIsVersionedPermanentAndPreservesSubjectReferences(){
        active();var revoked=success(http.postForEntity("/api/v1/caller-credentials/"+credential+"/revoke",Map.of("version",1,"reason","测试撤销"),JsonNode.class));assertThat(revoked.path("version").asInt()).isEqualTo(2);denied(request(),"IDENTITY_INVALID");
        assertThat(http.postForEntity("/api/v1/caller-credentials/"+credential+"/revoke",Map.of("version",1,"reason","旧版本"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var events=success(http.getForEntity("/api/v1/caller-credentials/"+credential+"/events",JsonNode.class));assertThat(events.size()).isEqualTo(2);assertThat(events.get(1).path("action").asText()).isEqualTo("REVOKE");
        db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",demand);assertThat(http.exchange("/api/v1/subjects/"+demand,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String unbound=subject("DEMAND");success(issue(unbound,"仅凭据无关系",millis(3600)));db.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",unbound);
        var deletion=http.exchange("/api/v1/subjects/"+unbound,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class);assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(deletion.getBody().path("message").asText()).contains("凭据");
    }
    @Test void expiredCredentialAndExpiredOrFutureRelationshipAreDenied(){
        active();db.update("UPDATE caller_credential SET expires_at='2025-01-01' WHERE credential_id=?",credential);denied(request(),"IDENTITY_INVALID");db.update("UPDATE caller_credential SET expires_at='2099-01-01' WHERE credential_id=?",credential);
        db.update("UPDATE sharing_relationship SET valid_from='2024-01-01',valid_until='2025-01-01' WHERE relationship_id=?",relationship);denied(request(),"RELATIONSHIP_EXPIRED");
        db.update("UPDATE sharing_relationship SET valid_from='2098-01-01',valid_until='2099-01-01' WHERE relationship_id=?",relationship);denied(request(),"RELATIONSHIP_EXPIRED");
    }
}
