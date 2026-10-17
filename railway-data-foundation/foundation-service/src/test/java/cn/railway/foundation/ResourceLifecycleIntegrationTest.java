package cn.railway.foundation;

import cn.railway.foundation.subscription.*;
import cn.railway.foundation.capability.KafkaCapabilityVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KAFKA_SUBSCRIPTION_EXECUTION_ENABLED=true","KAFKA_SUBSCRIPTION_RECONCILE_MS=3600000","PROVIDER_SOURCE_ENV_PREFIX=SOURCE_LIFE","SOURCE_LIFE_HOST=127.0.0.1","SOURCE_LIFE_PORT=13306","SOURCE_LIFE_DATABASE=life_schema","SOURCE_LIFE_USER=fixture_only","SOURCE_LIFE_PASSWORD=fixture_only_not_connected"})
class ResourceLifecycleIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;@Autowired SubscriptionExecutor executor;
    @Autowired cn.railway.foundation.credential.EncryptedCredentialService secrets;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SubscriptionBroker broker;
    @org.springframework.test.context.bean.override.mockito.MockitoBean KafkaCapabilityVerifier verifier;
    String source,scan,resource,provider,demand,rest,kafka,relation,policy,grant;
    SubscriptionBroker.Session brokerSession;
    String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    @BeforeEach void fixture(){
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("foundation_test");
        source=id("SRC");scan=id("SCAN");resource=id("RES");provider=id("SUB");demand=id("SUB");rest=id("CAP");kafka=id("CAP");relation=id("REL");policy=id("POL");grant=id("KSG");
        when(broker.enabled()).thenReturn(true);when(broker.configuredCluster()).thenReturn("lifecycle_fixture_cluster");brokerSession=mock(SubscriptionBroker.Session.class);when(broker.open(anyString(),anyBoolean())).thenReturn(brokerSession);
        when(broker.bootstrap()).thenReturn("127.0.0.1:19093");when(broker.protocol()).thenReturn("SASL_PLAINTEXT");
        for(var e:Map.of(provider,"PROVIDER",demand,"DEMAND").entrySet())db.update("INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,status,created_at,updated_at) VALUES(?,?,?,?,?,'ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",e.getKey(),e.getKey(),"生命周期独立主体",e.getValue(),"测试单位");
        db.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,status,provider_subject_id,created_at,updated_at) VALUES(?,'生命周期独立来源','MYSQL','SOURCE_LIFE','ENABLED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",source,provider);
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,field_count) VALUES(?,'life_schema','container_event',1)",scan);
        db.update("INSERT INTO metadata_scan_field(task_id,schema_name,table_name,field_name,data_type,column_type,nullable,primary_key,ordinal_position) VALUES(?,'life_schema','container_event','event_time','datetime','datetime(3)',true,false,1)",scan);
        var created=http.postForObject("/api/v1/resources",resourceBody(1),JsonNode.class);resource=created.path("data").path("resource_id").asText();assertThat(resource).startsWith("RES_");
    }
    Map<String,Object> resourceBody(int version){var b=new HashMap<String,Object>();b.put("source_id",source);b.put("scan_task_id",scan);b.put("schema_name","life_schema");b.put("physical_table","container_event");b.put("resource_name","生命周期独立资源");b.put("resource_type","TABLE");b.put("business_description","不连接业务源的独立元数据");b.put("owner_department","测试单位");b.put("security_level","公开");b.put("update_mode","实时");b.put("version",version);return b;}
    ResponseEntity<JsonNode> change(String action,int version){return http.postForEntity("/api/v1/resources/"+resource+"/"+action,Map.of("version",version,"reason","生命周期独立验证"),JsonNode.class);}
    void published(){assertThat(change("publish",1).getStatusCode()).isEqualTo(HttpStatus.OK);}
    void bindings(){
        published();for(var e:Map.of(rest,"REST_API",kafka,"KAFKA_TOPIC").entrySet())db.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,http_method,endpoint,topic,protocol,auth_type,status,health_status,created_at,updated_at) VALUES(?,?,?,?,'GET','/containers/events','life.topic',?,?,'ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",e.getKey(),resource,e.getValue(),e.getValue(),e.getValue().equals("REST_API")?"HTTP":"KAFKA",e.getValue().equals("REST_API")?"NONE":"SASL");
        db.update("INSERT INTO access_policy(policy_id,resource_id,allowed_fields,time_field,data_from,data_until,created_at,updated_at) VALUES(?,?,JSON_ARRAY('event_time'),'event_time','2026-01-01','2027-01-01',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",policy,resource);
        db.update("INSERT INTO sharing_relationship(relationship_id,relationship_name,provider_subject_id,demand_subject_id,resource_id,capability_id,policy_id,purpose,valid_from,valid_until,status,confirmed_at,created_at,updated_at) VALUES(?,'生命周期REST',?,?,?,?,?,'测试',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",relation,provider,demand,resource,rest,policy);
        db.update("INSERT INTO kafka_subscription_grant(grant_id,grant_name,provider_subject_id,demand_subject_id,resource_id,capability_id,topic,auth_type,consumer_group,scope,purpose,valid_from,valid_until,status,confirmed_at,created_at,updated_at) VALUES(?,'生命周期Kafka',?,?,?,?,'life.topic','SASL',?,'FULL_TOPIC','测试',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),'APPROVED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",grant,provider,demand,resource,kafka,"life."+grant);
        db.update("INSERT INTO kafka_subscription_execution(grant_id,broker_username,cluster_id,desired_state,execution_state,encrypted_password,issued_at,consumption_token_digest,updated_at) VALUES(?,?,'lifecycle_fixture_cluster','GRANTED','ACTIVE','fixture-only',UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3))",grant,"rdfk_"+UUID.randomUUID().toString().replace("-",""),"a".repeat(64));
    }
    @AfterEach void cleanup(){
        for(String table:List.of("kafka_consumption_report","kafka_subscription_execution","kafka_subscription_event"))db.update("DELETE FROM "+table+" WHERE grant_id IN (SELECT grant_id FROM kafka_subscription_grant WHERE resource_id=?)",resource);
        db.update("DELETE FROM kafka_subscription_grant WHERE resource_id=?",resource);db.update("DELETE FROM sharing_relationship_event WHERE relationship_id IN (SELECT relationship_id FROM sharing_relationship WHERE resource_id=?)",resource);db.update("DELETE FROM sharing_relationship WHERE resource_id=?",resource);db.update("DELETE FROM access_policy WHERE resource_id=?",resource);db.update("DELETE FROM capability WHERE resource_id=?",resource);db.update("DELETE FROM resource_field WHERE resource_id=?",resource);db.update("DELETE FROM data_resource WHERE resource_id=?",resource);db.update("DELETE FROM metadata_scan_field WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_table WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_task WHERE task_id=?",scan);db.update("DELETE FROM source_system WHERE source_id=?",source);db.update("DELETE FROM sharing_subject WHERE subject_id IN (?,?)",provider,demand);
    }
    @Test void lifecycleStatesHistoryReasonsAndVersionGuards(){
        assertThat(http.postForEntity("/api/v1/resources/"+resource+"/publish",Map.of("reason","缺少版本"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        published();assertThat(change("retire",2).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(change("unpublish",1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(change("unpublish",2).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(change("republish",3).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(change("unpublish",4).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(change("retire",5).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change("republish",6).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(http.exchange("/api/v1/resources/"+resource,HttpMethod.PUT,new HttpEntity<>(resourceBody(6)),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var history=http.getForObject("/api/v1/resources/"+resource+"/versions",JsonNode.class).path("data").path("items");assertThat(history.size()).isEqualTo(6);assertThat(history.get(0).path("action").asText()).isEqualTo("RETIRE");assertThat(history.get(0).path("operation_reason").asText()).isEqualTo("生命周期独立验证");assertThat(history.get(0).path("operator_name").asText()).isNotBlank();
        assertThat(http.postForEntity("/api/v1/resources",resourceBody(1),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void unpublishPersistsRevocationAndRepublishDoesNotRestoreAuthorizations()throws Exception{
        bindings();var result=change("unpublish",2);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody().path("data").path("impact").path("kafka_revoke_pending").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM sharing_relationship WHERE relationship_id=?",String.class,relation)).isEqualTo("SUSPENDED");assertThat(db.queryForObject("SELECT confirmed_at IS NOT NULL FROM sharing_relationship WHERE relationship_id=?",Boolean.class,relation)).isTrue();
        assertThat(db.queryForObject("SELECT consumption_token_digest FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant)).isNull();assertThat(db.queryForObject("SELECT status FROM kafka_subscription_grant WHERE grant_id=?",String.class,grant)).isEqualTo("APPROVED");
        assertThat(change("republish",3).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(db.queryForObject("SELECT status FROM sharing_relationship WHERE relationship_id=?",String.class,relation)).isEqualTo("SUSPENDED");
        when(broker.open(anyString(),anyBoolean())).thenThrow(new IllegalStateException("fixture offline"));executor.reconcile(grant);assertThat(db.queryForObject("SELECT desired_state FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant)).isEqualTo("REVOKED");assertThat(db.queryForObject("SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant)).isEqualTo("FAILED");
        doReturn(brokerSession).when(broker).open(anyString(),anyBoolean());executor.reconcile(grant);verify(brokerSession).revoke(anyString());verify(brokerSession,never()).grant(anyString(),anyString(),anyString(),anyString());
        assertThat(db.queryForObject("SELECT execution_state FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant)).isEqualTo("REVOKED");
    }
    @Test void retireTerminatesRemainingApplicationsAndKeepsPoliciesAndCapabilities(){
        bindings();change("unpublish",2);change("retire",3);assertThat(db.queryForObject("SELECT status FROM sharing_relationship WHERE relationship_id=?",String.class,relation)).isEqualTo("REVOKED");assertThat(db.queryForObject("SELECT status FROM kafka_subscription_grant WHERE grant_id=?",String.class,grant)).isEqualTo("REVOKED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM capability WHERE resource_id=?",Integer.class,resource)).isEqualTo(2);assertThat(db.queryForObject("SELECT COUNT(*) FROM access_policy WHERE resource_id=?",Integer.class,resource)).isEqualTo(1);
        assertThat(http.postForEntity("/api/v1/capabilities/"+rest+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/v1/resources/"+resource+"/fields/event_time",HttpMethod.PUT,new HttpEntity<>(Map.of("business_name","禁止修改","time_field",true,"sensitivity_level","内部","resource_version",4)),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void publicationChecksSavedSnapshotAndSourceWithoutConnecting(){
        db.update("UPDATE source_system SET status='DISABLED' WHERE source_id=?",source);assertThat(change("publish",1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);db.update("UPDATE source_system SET status='ENABLED' WHERE source_id=?",source);
        db.update("DELETE FROM resource_field WHERE resource_id=?",resource);assertThat(change("publish",1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,resource)).isEqualTo(1);
    }
    @Test void deletionRequiresVersionAndOnlyDeletesUnreferencedDraft(){
        assertThat(http.exchange("/api/v1/resources/"+resource,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/v1/resources/"+resource+"?version=2",HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/api/v1/resources/"+resource+"?version=1",HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_field WHERE resource_id=?",Integer.class,resource)).isZero();
    }
    @Test void failedHistoryInsertRollsBackResourceRelationshipsAndRevocation(){
        bindings();db.update("INSERT INTO resource_revision(resource_id,version,action,recorded_at,resource_snapshot,fields_snapshot) VALUES(?,3,'BASELINE',UTC_TIMESTAMP(3),JSON_OBJECT(),JSON_ARRAY())",resource);
        assertThat(change("unpublish",2).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(db.queryForObject("SELECT status FROM data_resource WHERE resource_id=?",String.class,resource)).isEqualTo("PUBLISHED");assertThat(db.queryForObject("SELECT status FROM sharing_relationship WHERE relationship_id=?",String.class,relation)).isEqualTo("ACTIVE");assertThat(db.queryForObject("SELECT desired_state FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant)).isEqualTo("GRANTED");
    }
    @Test void concurrentEditAndUnpublishHaveOneWinner()throws Exception{
        bindings();var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);try{
            var down=pool.submit(()->{go.await();return change("unpublish",2);});var edit=pool.submit(()->{go.await();return http.exchange("/api/v1/resources/"+resource,HttpMethod.PUT,new HttpEntity<>(resourceBody(2)),JsonNode.class);});go.countDown();assertThat(List.of(down.get().getStatusCode().value(),edit.get().getStatusCode().value())).containsExactlyInAnyOrder(200,409);
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentProvisionAndUnpublishCannotRestoreGrantedIntent()throws Exception{
        bindings();db.update("DELETE FROM kafka_subscription_execution WHERE grant_id=?",grant);
        db.update("UPDATE sharing_relationship SET status='CONFIRMED' WHERE relationship_id=?",relation);
        var request=Map.of("relationship_name","并发新申请","provider_subject_id",provider,"demand_subject_id",demand,"resource_id",resource,"capability_id",rest,"purpose","并发登记","valid_from",java.time.Instant.now().minusSeconds(60).toString(),"valid_until",java.time.Instant.now().plusSeconds(3600).toString(),"reason","独立并发验证","policy",Map.of("allowed_fields",List.of("event_time"),"time_field","event_time","data_from","2026-01-01T00:00:00Z","data_until","2027-01-01T00:00:00Z"));
        var pool=Executors.newFixedThreadPool(4);var go=new CountDownLatch(1);try{
            var down=pool.submit(()->{go.await();return change("unpublish",2);});var provision=pool.submit(()->{go.await();return http.postForEntity("/api/v1/kafka-subscriptions/"+grant+"/provision",Map.of("version",1,"reason","并发配置"),JsonNode.class);});
            var enable=pool.submit(()->{go.await();return http.postForEntity("/api/v1/relationships/"+relation+"/enable",Map.of("version",1,"reason","并发启用"),JsonNode.class);});var create=pool.submit(()->{go.await();return http.postForEntity("/api/v1/relationships",request,JsonNode.class);});
            go.countDown();assertThat(down.get().getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(provision.get().getStatusCode().value()).isIn(200,400,409);assertThat(enable.get().getStatusCode().value()).isIn(200,400,409);assertThat(create.get().getStatusCode().value()).isIn(200,400,409);
        }finally{pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kafka_subscription_execution WHERE grant_id=? AND desired_state='GRANTED'",Integer.class,grant)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sharing_relationship WHERE resource_id=? AND status='ACTIVE'",Integer.class,resource)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM access_policy WHERE resource_id=?",Integer.class,resource)).isEqualTo(db.queryForObject("SELECT COUNT(*) FROM sharing_relationship WHERE resource_id=?",Integer.class,resource));
    }
    @Test void concurrentCredentialClaimAndUnpublishCannotKeepUsableReportToken()throws Exception{
        bindings();String user=db.queryForObject("SELECT broker_username FROM kafka_subscription_execution WHERE grant_id=?",String.class,grant);
        db.update("UPDATE kafka_subscription_execution SET issued_at=NULL,encrypted_password=? WHERE grant_id=?",secrets.sealSubscription(grant,user,"ks_"+"p".repeat(43)),grant);
        var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);try{
            var down=pool.submit(()->{go.await();return change("unpublish",2);});var claim=pool.submit(()->{go.await();return http.postForEntity("/api/v1/kafka-subscriptions/"+grant+"/credential",Map.of("version",1,"reason","并发领取"),JsonNode.class);});go.countDown();assertThat(down.get().getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(claim.get().getStatusCode().value()).isIn(200,400,409);
        }finally{pool.shutdownNow();}
        var row=db.queryForMap("SELECT consumption_token_digest,encrypted_password,desired_state FROM kafka_subscription_execution WHERE grant_id=?",grant);assertThat(row.get("consumption_token_digest")).isNull();assertThat(row.get("encrypted_password")).isNull();assertThat(row.get("desired_state")).isEqualTo("REVOKED");
    }
    @Test @EnabledIfEnvironmentVariable(named="RUN_RESOURCE_LIFECYCLE_BROWSER",matches="true") void actualBrowserLifecycleAndFailureRecovery()throws Exception{
        bindings();var web=java.nio.file.Path.of("..","foundation-web").toAbsolutePath().normalize();var evidence=java.nio.file.Files.createTempDirectory(web.resolve("../.runtime"),"resource-lifecycle-browser-");var config=evidence.resolve("context.json");java.nio.file.Files.writeString(config,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("foundation",http.getRootUri(),"resource_id",resource,"evidence",evidence.toString())));
        String node=System.getenv().getOrDefault("ROLE_NODE_EXECUTABLE","node");var builder=new ProcessBuilder(node,"scripts/resource-lifecycle-live.mjs",config.toString()).directory(web.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());builder.environment().put("RESOURCE_LIFE_USER",testAdminUsername);builder.environment().put("RESOURCE_LIFE_PASSWORD",testAdminPassword);var process=builder.start();try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("Lifecycle browser evidence: "+evidence).isZero();}finally{process.destroyForcibly();}
    }

}
