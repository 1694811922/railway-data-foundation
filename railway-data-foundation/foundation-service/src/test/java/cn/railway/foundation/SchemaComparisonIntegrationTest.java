package cn.railway.foundation;

import cn.railway.foundation.resource.SchemaComparisonService;
import cn.railway.foundation.source.SourceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaComparisonIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired SourceMapper sources;
    @Autowired SchemaComparisonService comparison;
    @Autowired TransactionTemplate transactions;
    String source,baseline,resource,table,capability,provider,demand,relationship,policy;
    final List<String> sourceIds=new ArrayList<>();
    static URI uri(){return URI.create(System.getenv("TEST_SECOND_SOURCE_DB_URL").substring(5));}
    static String schema(){return uri().getPath().substring(1);}
    private void ddl(String sql) throws Exception {
        if(!schema().equals("another_source_test") || !table.matches("schema_compare_[a-f0-9]{32}"))throw new IllegalStateException("Dedicated fixture only");
        try(var connection=DriverManager.getConnection(System.getenv("TEST_SECOND_SOURCE_DB_URL"),System.getenv("TEST_SOURCE_DB_USER"),System.getenv("TEST_SOURCE_DB_PASSWORD"));
                var statement=connection.createStatement()){statement.execute(sql);}
    }
    private JsonNode post(String path,Object body) {
        var response=http.postForEntity("/api/v1"+path,body,JsonNode.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("code").asInt()).isZero();return response.getBody().path("data");
    }
    private JsonNode get(String path){return http.getForObject("/api/v1"+path,JsonNode.class).path("data");}
    private String source() {
        var request=new HashMap<String,Object>();request.put("source_name","结构比较测试");request.put("source_type","MYSQL");
        request.put("host",uri().getHost());request.put("port",uri().getPort());request.put("database_name",schema());
        request.put("username",System.getenv("TEST_SOURCE_DB_USER"));request.put("password",System.getenv("TEST_SOURCE_DB_PASSWORD"));request.put("ssl_mode","DISABLED");
        String id=post("/sources",request).path("source_id").asText();sourceIds.add(id);return id;
    }
    private String scan(){var data=post("/sources/"+source+"/scan",null);assertThat(data.path("status").asText()).isEqualTo("SUCCESS");return data.path("task_id").asText();}
    private JsonNode compare(String target){return get("/resources/"+resource+"/schema-comparison?scanTaskId="+target);}
    private String endpoint(){return "/api/v1/resources/"+resource+"/schema-comparison";}
    @BeforeEach void setup() throws Exception {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("foundation_test");
        table="schema_compare_"+UUID.randomUUID().toString().replace("-","");
        ddl("CREATE TABLE "+table+"(id BIGINT NOT NULL PRIMARY KEY,code VARCHAR(10) NULL DEFAULT NULL COMMENT '旧注释',event_time DATETIME NOT NULL,old_code INT NULL DEFAULT 0) COMMENT='原表注释'");
        source=source();baseline=scan();
        resource=post("/resources",Map.of("resource_name","结构比较资源","source_id",source,"scan_task_id",baseline,"schema_name",schema(),
                "physical_table",table,"business_description","人工登记","owner_department","测试单位","security_level","内部","update_mode","实时")).path("resource_id").asText();
    }
    @AfterEach void cleanup() throws Exception {
        if(relationship!=null){db.update("DELETE FROM sharing_relationship_event WHERE relationship_id=?",relationship);db.update("DELETE FROM sharing_relationship WHERE relationship_id=?",relationship);db.update("DELETE FROM access_policy WHERE policy_id=?",policy);}
        if(provider!=null)db.update("DELETE FROM sharing_subject WHERE subject_id=?",provider);
        if(demand!=null)db.update("DELETE FROM sharing_subject WHERE subject_id=?",demand);
        for(String id:sourceIds){
            String ref=sources.selectById(id).credentialRef;
            db.update("DELETE FROM capability WHERE resource_id IN (SELECT resource_id FROM data_resource WHERE source_id=?)",id);
            db.update("DELETE FROM resource_field WHERE resource_id IN (SELECT resource_id FROM data_resource WHERE source_id=?)",id);
            db.update("DELETE FROM data_resource WHERE source_id=?",id);
            db.update("DELETE FROM metadata_scan_field WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",id);
            db.update("DELETE FROM metadata_scan_table WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",id);
            db.update("DELETE FROM metadata_scan_task WHERE source_id=?",id);sources.deleteById(id);db.update("DELETE FROM source_credential WHERE credential_ref=?",ref);
        }
        ddl("DROP TABLE IF EXISTS "+table+"_scope");ddl("DROP TABLE IF EXISTS "+table);
    }
    @Test void identicalScanHasNoChangesAndCandidatesAreScopedPagedAndStable() {
        String target=scan();var result=compare(target);
        assertThat(result.path("comparison_status").asText()).isEqualTo("UNCHANGED");
        assertThat(result.path("summary").path("unchanged").asInt()).isEqualTo(4);
        assertThat(result.path("changes").size()).isZero();assertThat(result.path("resource_version").asInt()).isEqualTo(1);
        String other=source();post("/sources/"+other+"/scan",null);
        String newer=scan();db.update("UPDATE metadata_scan_task SET started_at=DATE_ADD(started_at,INTERVAL 1 SECOND) WHERE task_id=?",newer);
        var page=get("/resources/"+resource+"/schema-comparison/scans?limit=1&offset=0");
        assertThat(page.path("total").asInt()).isEqualTo(2);assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("task_id").asText()).isEqualTo(newer);
        assertThat(get("/resources/"+resource+"/schema-comparison/scans?limit=1&offset=1").path("items").get(0).path("task_id").asText()).isEqualTo(target);
        for(String query:List.of("limit=0","limit=201","offset=-1"))assertThat(http.getForEntity(endpoint()+"/scans?"+query,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void realAlterRenameAndAttributeDifferencesNeverChangeRegisteredGovernance() throws Exception {
        var response=http.exchange("/api/v1/resources/"+resource+"/fields/event_time",HttpMethod.PUT,
                new HttpEntity<>(Map.of("business_name","业务时间","business_description","人工维护","time_field",true,"sensitivity_level","敏感","resource_version",1)),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);post("/resources/"+resource+"/publish",null);
        capability=post("/capabilities",Map.of("resource_id",resource,"capability_name","结构比较能力","type","REST_API","http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE")).path("capability_id").asText();
        provider=subject("PROVIDER");demand=subject("DEMAND");
        var body=new HashMap<String,Object>();body.put("relationship_name","结构比较关系");body.put("provider_subject_id",provider);body.put("demand_subject_id",demand);body.put("resource_id",resource);body.put("capability_id",capability);
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        body.put("purpose","验证只读比较");body.put("reason","登记测试");body.put("valid_from",now.minusSeconds(60).toString());body.put("valid_until",now.plusSeconds(3600).toString());
        body.put("policy",Map.of("allowed_fields",List.of("code","event_time"),"time_field","event_time","data_from","2026-01-01T00:00:00Z","data_until","2026-12-01T00:00:00Z"));
        var relation=post("/relationships",body);relationship=relation.path("relationship_id").asText();policy=relation.path("policy").path("policy_id").asText();
        var resourceBefore=get("/resources/"+resource);var fieldsBefore=get("/resources/"+resource+"/fields");var capBefore=get("/capabilities/"+capability);
        ddl("ALTER TABLE "+table+" DROP PRIMARY KEY, MODIFY id INT NULL, RENAME COLUMN old_code TO new_code, MODIFY code VARCHAR(40) NOT NULL DEFAULT 'active' COMMENT '新注释' AFTER event_time, ADD PRIMARY KEY(code), COMMENT='新表注释'");
        String target=scan();var result=compare(target);
        assertThat(result.path("comparison_status").asText()).isEqualTo("CHANGED");
        assertThat(result.path("summary").path("added").asInt()).isEqualTo(1);assertThat(result.path("summary").path("removed").asInt()).isEqualTo(1);
        assertThat(result.path("changes").get(0).path("field_name").asText()).isEqualTo("new_code");
        assertThat(result.path("changes").get(1).path("field_name").asText()).isEqualTo("old_code");
        var ordered=new ArrayList<String>();result.path("changes").forEach(row->ordered.add(row.path("field_name").asText()));
        assertThat(ordered).containsExactly("new_code","old_code","id","event_time","code");
        var changes=new HashMap<String,JsonNode>();result.path("changes").forEach(row->changes.put(row.path("field_name").asText(),row));
        assertThat(changes.get("id").path("changed_properties").toString()).contains("data_type","column_type","primary_key","nullable");
        assertThat(changes.get("code").path("changed_properties").toString()).contains("column_type","primary_key","nullable","default_value","technical_comment","ordinal_position");
        assertThat(result.path("table_changes").get(0).path("after").asText()).isEqualTo("新表注释");
        assertThat(get("/resources/"+resource)).isEqualTo(resourceBefore);assertThat(get("/resources/"+resource+"/fields")).isEqualTo(fieldsBefore);
        assertThat(get("/capabilities/"+capability)).isEqualTo(capBefore);assertThat(get("/relationships/"+relationship)).isEqualTo(relation);
    }
    private String subject(String type){return post("/subjects",Map.of("subject_code","DIFF_"+UUID.randomUUID().toString().replace("-","").toUpperCase(),"subject_name","比较测试","subject_type",type,"owner_department","测试单位")).path("subject_id").asText();}
    @Test void defaultNullEmptyAndLiteralNullAreDifferentAndRowEstimateIsIgnored() {
        String target=scan();db.update("UPDATE metadata_scan_field SET default_value='' WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        var row=compare(target).path("changes").get(0);assertThat(row.path("before").path("default_value").isNull()).isTrue();assertThat(row.path("after").path("default_value").asText()).isEmpty();
        db.update("UPDATE metadata_scan_field SET default_value='NULL' WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        assertThat(compare(target).path("changes").get(0).path("after").path("default_value").asText()).isEqualTo("NULL");
        db.update("UPDATE metadata_scan_field SET default_value=NULL WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        db.update("UPDATE metadata_scan_table SET estimated_rows=99999 WHERE task_id=? AND table_name=?",target,table);
        assertThat(compare(target).path("comparison_status").asText()).isEqualTo("UNCHANGED");
        db.update("UPDATE metadata_scan_table SET table_comment='仅注释修改' WHERE task_id=? AND table_name=?",target,table);
        var comment=compare(target);assertThat(comment.path("comparison_status").asText()).isEqualTo("CHANGED");assertThat(comment.path("changes").size()).isZero();
    }
    @Test void rejectsFailedForeignOlderMissingAndIncompleteSnapshots() {
        String target=scan();String foreign=source();String foreignTask=post("/sources/"+foreign+"/scan",null).path("task_id").asText();
        for(String task:List.of(baseline,foreignTask))assertThat(http.getForEntity(endpoint()+"?scanTaskId="+task,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for(String state:List.of("PENDING","RUNNING","FAILED")) {
            db.update("UPDATE metadata_scan_task SET status=? WHERE task_id=?",state,target);
            assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        db.update("UPDATE metadata_scan_task SET status='SUCCESS',started_at=DATE_SUB((SELECT t.started_at FROM (SELECT started_at FROM metadata_scan_task WHERE task_id=?)t),INTERVAL 1 SECOND) WHERE task_id=?",baseline,target);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE metadata_scan_task SET started_at=UTC_TIMESTAMP(3) WHERE task_id=?",target);
        db.update("UPDATE metadata_scan_field SET ordinal_position=1 WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE metadata_scan_field SET ordinal_position=2 WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        db.update("UPDATE metadata_scan_table SET field_count=5 WHERE task_id=? AND table_name=?",baseline,table);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE metadata_scan_table SET field_count=4 WHERE task_id=? AND table_name=?",baseline,table);
        db.update("DELETE FROM metadata_scan_field WHERE task_id=? AND table_name=? AND field_name='code'",target,table);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId=SCAN_"+"a".repeat(32),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId=bad",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity(endpoint(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/resources/RES_missing/schema-comparison/scans",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/resources/"+resource).path("version").asInt()).isEqualTo(1);
    }
    @Test void missingTableAndEmptyScopeKeepCountsUnknown() throws Exception {
        ddl("CREATE TABLE "+table+"_scope(id INT NOT NULL PRIMARY KEY)");
        ddl("DROP TABLE "+table);String target=scan();
        var result=compare(target);
        assertThat(result.path("comparison_status").asText()).isEqualTo("TABLE_NOT_FOUND");
        assertThat(result.path("summary").isNull()).isTrue();assertThat(result.path("changes").size()).isZero();
        db.update("DELETE FROM metadata_scan_field WHERE task_id=?",target);db.update("DELETE FROM metadata_scan_table WHERE task_id=?",target);
        db.update("UPDATE metadata_scan_task SET table_count=0 WHERE task_id=?",target);
        assertThat(compare(target).path("comparison_status").asText()).isEqualTo("SCOPE_UNCONFIRMED");
        db.update("UPDATE metadata_scan_task SET table_count=1 WHERE task_id=?",target);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void differentSchemaIsRejectedAndDeletionCannotTearAReadSnapshot() throws Exception {
        String target=scan();
        // Update foreign-key-related rows together only in this dedicated test snapshot.
        db.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES(?,'other_schema','other_table','',1)",target);
        db.update("INSERT INTO metadata_scan_field(task_id,schema_name,table_name,field_name,data_type,column_type,nullable,primary_key,default_value,technical_comment,ordinal_position) VALUES(?,'other_schema','other_table','id','int','int',false,true,NULL,'',1)",target);
        db.update("UPDATE metadata_scan_task SET table_count=table_count+1 WHERE task_id=?",target);
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("DELETE FROM metadata_scan_field WHERE task_id=? AND schema_name='other_schema'",target);
        db.update("DELETE FROM metadata_scan_table WHERE task_id=? AND schema_name='other_schema'",target);db.update("UPDATE metadata_scan_task SET table_count=table_count-1 WHERE task_id=?",target);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var snapshot=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
            snapshot.setReadOnly(true);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.executeWithoutResult(status->{
                // Establish a snapshot, delete the unreferenced target in a separate HTTP transaction, then compare inside it.
                assertThat(db.queryForObject("SELECT COUNT(*) FROM metadata_scan_task WHERE task_id=?",Integer.class,target)).isEqualTo(1);
                try { assertThat(pool.submit(()->http.exchange("/api/v1/scan-tasks/"+target,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class)).get(10,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK); }
                catch(Exception error){throw new IllegalStateException(error);}
                var complete=comparison.compare(resource,target);assertThat(complete.comparisonStatus()).isEqualTo("UNCHANGED");assertThat(complete.summary().unchanged()).isEqualTo(4);
            });
        } finally {pool.shutdownNow();}
        assertThat(http.getForEntity(endpoint()+"?scanTaskId="+target,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
