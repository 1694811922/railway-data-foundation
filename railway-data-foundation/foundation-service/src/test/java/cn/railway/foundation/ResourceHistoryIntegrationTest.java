package cn.railway.foundation;
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

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceHistoryIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    String source,scan,resource;
    static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    JsonNode ok(ResponseEntity<JsonNode> response){assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getBody().path("code").asInt()).isZero();return response.getBody().path("data");}
    JsonNode get(String path){return ok(http.getForEntity("/api/v1/resources/"+resource+path,JsonNode.class));}
    ResponseEntity<JsonNode> edit(int version,String name){return http.exchange("/api/v1/resources/"+resource+"/fields/event_time",HttpMethod.PUT,new HttpEntity<>(Map.of("business_name",name,"business_description","业务时间说明","time_field",true,"sensitivity_level","内部","resource_version",version)),JsonNode.class);}
    @BeforeEach void setup(){
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("foundation_test");source=id("SRC");scan=id("SCAN");
        db.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,status,created_at,updated_at) VALUES(?,'版本历史来源','MYSQL','SOURCE_TEST','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",source);
        db.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,field_count) VALUES(?,'history_schema','events',2)",scan);
        for(var name:List.of("id","event_time"))db.update("INSERT INTO metadata_scan_field(task_id,schema_name,table_name,field_name,data_type,column_type,nullable,primary_key,ordinal_position) VALUES(?,'history_schema','events',?,?,?,?,?,?)",scan,name,name.equals("id")?"bigint":"datetime",name.equals("id")?"bigint":"datetime(3)",false,name.equals("id"),name.equals("id")?1:2);
        resource=ok(http.postForEntity("/api/v1/resources",Map.of("resource_name","历史测试资源","source_id",source,"scan_task_id",scan,"schema_name","history_schema","physical_table","events","business_description","登记说明","owner_department","科研组","security_level","内部","update_mode","实时"),JsonNode.class)).path("resource_id").asText();
    }
    @AfterEach void cleanup(){db.update("DELETE FROM resource_field WHERE resource_id=?",resource);db.update("DELETE FROM data_resource WHERE resource_id=?",resource);db.update("DELETE FROM metadata_scan_field WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_table WHERE task_id=?",scan);db.update("DELETE FROM metadata_scan_task WHERE task_id=?",scan);db.update("DELETE FROM source_system WHERE source_id=?",source);}
    @Test void capturesCreateResourceEditFieldEditAndPublishWithFrozenSnapshots(){
        var first=get("/versions/1");assertThat(first.path("snapshot").path("entry").path("action").asText()).isEqualTo("CREATE");assertThat(first.path("previous_snapshot").isNull()).isTrue();
        var body=new HashMap<String,Object>();var current=get("");
        for(var key:List.of("resource_name","source_id","scan_task_id","schema_name","physical_table","business_description","owner_department","security_level","update_mode"))body.put(key,current.path(key).asText());
        body.put("version",1);body.put("resource_name","新的资源名称");ok(http.exchange("/api/v1/resources/"+resource,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class));
        ok(edit(2,"业务发生时间"));ok(http.postForEntity("/api/v1/resources/"+resource+"/publish",null,JsonNode.class));
        assertThat(get("/versions").path("total").asInt()).isEqualTo(4);
        assertThat(get("/versions/2").path("snapshot").path("entry").path("action").asText()).isEqualTo("RESOURCE_UPDATE");
        var third=get("/versions/3");assertThat(third.path("snapshot").path("entry").path("changed_field").asText()).isEqualTo("event_time");
        assertThat(third.path("snapshot").path("fields").get(1).path("business_name").asText()).isEqualTo("业务发生时间");
        assertThat(third.path("previous_snapshot").path("fields").get(1).path("business_name").asText()).isEmpty();
        assertThat(get("/versions/4").path("snapshot").path("resource").path("status").asText()).isEqualTo("PUBLISHED");
        db.update("UPDATE source_system SET source_name='重命名来源' WHERE source_id=?",source);
        assertThat(get("/versions/1")).isEqualTo(first);
        assertThat(get("/versions/4").toString()).doesNotContain("password","credential_ref","connection_env_prefix","host");
    }
    @Test void conflictAndSnapshotFailureRollBackWithoutAddingHistory(){
        ok(edit(1,"第一次维护"));var before=get("");var fields=get("/fields");
        assertThat(edit(1,"过期维护").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/versions").path("total").asInt()).isEqualTo(2);
        // Force the next snapshot insert to fail without changing the production service.
        db.update("INSERT INTO resource_revision(resource_id,version,action,changed_field,recorded_at,resource_snapshot,fields_snapshot) SELECT resource_id,3,action,changed_field,recorded_at,resource_snapshot,fields_snapshot FROM resource_revision WHERE resource_id=? AND version=2",resource);
        assertThat(edit(2,"不能提交的维护").getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(get("")).isEqualTo(before);assertThat(get("/fields")).isEqualTo(fields);
        assertThat(get("/versions").path("total").asInt()).isEqualTo(3);
    }
    @Test void concurrentSameVersionWritesHaveExactlyOneSnapshot()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{gate.await();return edit(1,"甲修改");});var b=pool.submit(()->{gate.await();return edit(1,"乙修改");});gate.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS).getStatusCode(),b.get(10,TimeUnit.SECONDS).getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(get("/versions").path("total").asInt()).isEqualTo(2);
            assertThat(get("/versions/2").path("snapshot").path("fields")).isEqualTo(get("/fields"));
        }finally{pool.shutdownNow();}
    }
    @Test void paginationUnknownVersionsAndReadOnlyRequestsDoNotAlterCurrentState(){
        for(int version=1;version<=22;version++)ok(edit(version,"维护"+version));
        var before=get("");var fields=get("/fields");var page=get("/versions?limit=20&offset=0");
        assertThat(page.path("total").asInt()).isEqualTo(23);assertThat(page.path("items").size()).isEqualTo(20);
        assertThat(page.path("items").get(0).path("version").asInt()).isEqualTo(23);
        assertThat(get("/versions?limit=20&offset=20").path("items").size()).isEqualTo(3);
        for(var path:List.of("/versions/999","/versions/1")){var response=http.getForEntity("/api/v1/resources/"+resource+path,JsonNode.class);assertThat(response.getStatusCode()).isEqualTo(path.endsWith("999")?HttpStatus.NOT_FOUND:HttpStatus.OK);}
        for(var query:List.of("limit=0","limit=201","offset=-1"))assertThat(http.getForEntity("/api/v1/resources/"+resource+"/versions?"+query,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/resources/"+resource+"/versions/0",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/resources/RES_missing/versions",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("")).isEqualTo(before);assertThat(get("/fields")).isEqualTo(fields);
    }
    @Test void missingOldSnapshotsAreNotInventedAndDraftDeletionCleansOnlyOwnedHistory(){
        ok(edit(1,"新维护"));db.update("DELETE FROM resource_revision WHERE resource_id=? AND version=1",resource);
        assertThat(get("/versions/2").path("previous_snapshot").isNull()).isTrue();
        assertThat(http.getForEntity("/api/v1/resources/"+resource+"/versions/1",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ok(http.exchange("/api/v1/resources/"+resource,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_revision WHERE resource_id=?",Integer.class,resource)).isZero();
        assertThat(http.getForEntity("/api/v1/resources/"+resource+"/versions",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void migrationBackfillCapturesOnlyExistingCurrentVersionWithCorrectJsonTypes()throws Exception{
        ok(edit(1,"基线前的业务名称"));
        db.update("UPDATE data_resource SET version=7 WHERE resource_id=?",resource);
        db.update("DELETE FROM resource_revision WHERE resource_id=?",resource);
        var before=get("");var fields=get("/fields");
        var migration=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V11__resource_version_history.sql"));
        var insert=migration.substring(migration.indexOf("INSERT INTO resource_revision")).strip();
        insert=insert.substring(0,insert.length()-1)+" WHERE r.resource_id=?";
        db.update(insert,resource);
        var baseline=get("/versions/7");assertThat(baseline.path("snapshot").path("entry").path("action").asText()).isEqualTo("BASELINE");
        assertThat(baseline.path("previous_snapshot").isNull()).isTrue();
        assertThat(baseline.path("snapshot").path("resource")).isEqualTo(before);assertThat(baseline.path("snapshot").path("fields")).isEqualTo(fields);
        assertThat(get("/versions").path("total").asInt()).isEqualTo(1);
        assertThat(http.getForEntity("/api/v1/resources/"+resource+"/versions/6",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("")).isEqualTo(before);assertThat(get("/fields")).isEqualTo(fields);
    }
}
