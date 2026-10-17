package cn.railway.foundation;

import cn.railway.foundation.source.SourceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.kafka.test.context.EmbeddedKafka;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions=1,topics="port.container.event.v1",bootstrapServersProperty="FOUNDATION_KAFKA_BOOTSTRAP_SERVERS")
class ScanResourceIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired SourceMapper sources;
    String sourceId;
    static final AtomicBoolean providerReady=new AtomicBoolean(true);
    static final AtomicBoolean providerRuntimeReady=new AtomicBoolean(true);
    static final AtomicBoolean providerAccessReady=new AtomicBoolean(true);
    static final AtomicReference<Runnable> onProbe=new AtomicReference<>();
    static final HttpServer provider=fakeProvider();
    static HttpServer fakeProvider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/containers/events",exchange->{
                if (providerReady.get() && "HEAD".equals(exchange.getRequestMethod())) {
                    var callback=onProbe.getAndSet(null);
                    if (callback!=null) callback.run();
                    exchange.getResponseHeaders().add("X-Railway-Provider","container-event-v1");
                    if (providerRuntimeReady.get()) exchange.getResponseHeaders().add("X-Railway-Runtime","rest-capability-v1");
                    if (providerAccessReady.get()) exchange.getResponseHeaders().add("X-Railway-Access","sharing-access-v1");
                    exchange.getResponseHeaders().add("X-Railway-Source-Host",uri().getHost());
                    exchange.getResponseHeaders().add("X-Railway-Source-Port",Integer.toString(uri().getPort()));
                    exchange.getResponseHeaders().add("X-Railway-Source-Database",schema());
                    exchange.getResponseHeaders().add("X-Railway-Source-Table","container_event");
                    exchange.sendResponseHeaders(204,-1);
                } else exchange.sendResponseHeaders(503,-1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void closeProvider() { provider.stop(0); }
    static URI uri() { return URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5)); }
    static String schema() { return uri().getPath().substring(1); }
    @DynamicPropertySource static void props(DynamicPropertyRegistry props) {
        props.add("SOURCE_SCAN_HOST",()->uri().getHost());
        props.add("SOURCE_SCAN_PORT",()->uri().getPort());
        props.add("SOURCE_SCAN_DATABASE",ScanResourceIntegrationTest::schema);
        props.add("SOURCE_SCAN_USER",()->System.getenv("TEST_SOURCE_DB_USER"));
        props.add("SOURCE_SCAN_PASSWORD",()->System.getenv("TEST_SOURCE_DB_PASSWORD"));
        props.add("SOURCE_SCAN_SSL_MODE",()->"DISABLED");
        props.add("PROVIDER_REST_BASE_URL",()->"http://127.0.0.1:"+provider.getAddress().getPort());
        props.add("PROVIDER_SOURCE_ENV_PREFIX",()->"SOURCE_SCAN");
        props.add("FOUNDATION_KAFKA_TOPIC",()->"port.container.event.v1");
    }
    @BeforeAll static void migrateSource() {
        Flyway.configure().dataSource(System.getenv("TEST_SOURCE_DB_URL"),System.getenv("TEST_SOURCE_DB_USER"),System.getenv("TEST_SOURCE_DB_PASSWORD"))
                .locations("filesystem:"+Path.of("..","sql","source").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
    }
    @BeforeEach void createSource() {
        providerReady.set(true);
        providerRuntimeReady.set(true);
        providerAccessReady.set(true);
        onProbe.set(null);
        var response=http.postForObject("/api/v1/sources",Map.of("source_name","扫描测试数据源","source_type","MYSQL",
                "connection_env_prefix","SOURCE_SCAN"),JsonNode.class);
        sourceId=response.path("data").path("source_id").asText();
        assertThat(sourceId).startsWith("SRC_");
    }
    @AfterEach void clean() {
        onProbe.set(null);
        String credentialRef=sources.selectById(sourceId).credentialRef;
        db.update("DELETE FROM capability WHERE resource_id IN (SELECT resource_id FROM data_resource WHERE source_id=?)",sourceId);
        db.update("DELETE FROM resource_field WHERE resource_id IN (SELECT resource_id FROM data_resource WHERE source_id=?)",sourceId);
        db.update("DELETE FROM data_resource WHERE source_id=?",sourceId);
        db.update("DELETE FROM metadata_scan_field WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",sourceId);
        db.update("DELETE FROM metadata_scan_table WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",sourceId);
        db.update("DELETE FROM metadata_scan_task WHERE source_id=?",sourceId);
        sources.deleteById(sourceId);
        if (credentialRef!=null) db.update("DELETE FROM source_credential WHERE credential_ref=?",credentialRef);
    }
    @Test void scanToPublishUsesRealMetadataAndHumanBusinessFields() {
        var scan=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data");
        assertThat(scan.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(scan.path("table_count").asInt()).isGreaterThanOrEqualTo(1);
        String taskId=scan.path("task_id").asText();
        var tables=http.getForObject("/api/v1/scan-tasks/"+taskId+"/tables",JsonNode.class).path("data");
        assertThat(tables.toString()).contains("container_event");
        var fields=http.getForObject("/api/v1/scan-tasks/"+taskId+"/tables/container_event/fields",JsonNode.class).path("data");
        assertThat(fields.size()).isEqualTo(12);
        assertThat(fields.get(0).path("field_name").asText()).isEqualTo("id");
        assertThat(fields.get(0).path("primary_key").asBoolean()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM metadata_scan_field WHERE task_id=?",Integer.class,taskId)).isEqualTo(12);

        var body=Map.of("resource_name","广州港集装箱作业事件数据集","source_id",sourceId,"scan_task_id",taskId,"schema_name",schema(),
                "physical_table","container_event","resource_type","TABLE","business_description","人工确认的作业事件含义",
                "owner_department","数据管理部","security_level","内部","update_mode","实时");
        var created=http.postForObject("/api/v1/resources",body,JsonNode.class).path("data");
        String id=created.path("resource_id").asText();
        assertThat(id).startsWith("RES_");
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(http.getForObject("/api/v1/resources/"+id+"/fields",JsonNode.class).path("data").size()).isEqualTo(12);
        var edit=http.exchange("/api/v1/resources/"+id+"/fields/event_time",HttpMethod.PUT,
                new HttpEntity<>(Map.of("business_name","事件发生时间","business_description","作业发生时刻",
                        "time_field",true,"sensitivity_level","内部","resource_version",1)),JsonNode.class);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edit.getBody().path("data").path("time_field").asBoolean()).isTrue();
        var publish=http.postForObject("/api/v1/resources/"+id+"/publish",Map.of("version",db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id),"reason","测试资源发布"),JsonNode.class).path("data");
        assertThat(publish.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(publish.path("version").asInt()).isGreaterThan(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='container_event'",Integer.class)).isZero();
    }
    @Test void rejectsUnscannedTableAndUnknownTask() {
        String taskId=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText();
        var invalid=Map.of("resource_name","虚构表","source_id",sourceId,"scan_task_id",taskId,"schema_name",schema(),"physical_table","missing_table",
                "business_description","无","owner_department","部门","security_level","内部","update_mode","每日");
        assertThat(http.postForEntity("/api/v1/resources",invalid,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/scan-tasks/SCAN_missing",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String scanSnapshot() {
        var response=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class);
        assertThat(response.path("data").path("status").asText()).isEqualTo("SUCCESS");
        return response.path("data").path("task_id").asText();
    }
    private Map<String,Object> snapshotResource(String taskId) {
        return new java.util.HashMap<>(Map.of("resource_name","扫描快照测试资源","source_id",sourceId,"scan_task_id",taskId,
                "schema_name",schema(),"physical_table","container_event","resource_type","TABLE",
                "business_description","人工核对所选扫描","owner_department","测试单位","security_level","内部","update_mode","实时"));
    }
    private Map<String,Object> fieldEdit(int version,String name) {
        return Map.of("business_name",name,"business_description","人工维护","time_field",true,
                "sensitivity_level","内部","resource_version",version);
    }
    private ResponseEntity<JsonNode> putResource(String id,Map<String,Object> body) {
        return http.exchange("/api/v1/resources/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class);
    }
    private ResponseEntity<JsonNode> putField(String id,String field,Map<String,Object> body) {
        return http.exchange("/api/v1/resources/"+id+"/fields/"+field,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class);
    }
    private JsonNode resourceFields(String id) { return http.getForObject("/api/v1/resources/"+id+"/fields",JsonNode.class).path("data"); }
    @Test void resourceAndFieldsRequireFreshVersionsAndConflictsNeverWrite() {
        var body=snapshotResource(scanSnapshot());
        String id=http.postForObject("/api/v1/resources",body,JsonNode.class).path("data").path("resource_id").asText();
        for(var field:resourceFields(id)) assertThat(field.path("resource_version").asInt()).isEqualTo(1);
        assertThat(putResource(id,body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        body.put("version",0);assertThat(putResource(id,body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var missing=new java.util.HashMap<>(fieldEdit(1,"未保存"));missing.remove("resource_version");
        assertThat(putField(id,"event_time",missing).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putField(id,"event_time",fieldEdit(0,"未保存")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        body.put("version",1);body.put("resource_name","新版资源名称");
        assertThat(putResource(id,body).getBody().path("data").path("version").asInt()).isEqualTo(2);
        var beforeFields=resourceFields(id);
        body.put("resource_name","旧页面覆盖");
        var conflict=putResource(id,body);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().path("code").asInt()).isEqualTo(10009);
        assertThat(putField(id,"event_time",fieldEdit(1,"旧字段覆盖")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resourceFields(id)).isEqualTo(beforeFields);
        assertThat(db.queryForObject("SELECT resource_name FROM data_resource WHERE resource_id=?",String.class,id)).isEqualTo("新版资源名称");
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(putField(id,"missing",fieldEdit(2,"未保存")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(2);
        var saved=putField(id,"event_time",fieldEdit(2,"事件发生时间"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().path("data").path("resource_version").asInt()).isEqualTo(3);
        for(var field:resourceFields(id)) assertThat(field.path("resource_version").asInt()).isEqualTo(3);
        assertThat(http.postForEntity("/api/v1/resources/"+id+"/publish",Map.of("version",db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id),"reason","测试资源发布"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putField(id,"event_time",fieldEdit(3,"发布前旧输入")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(4);
    }
    private List<ResponseEntity<JsonNode>> simultaneously(java.util.concurrent.Callable<ResponseEntity<JsonNode>> first,
            java.util.concurrent.Callable<ResponseEntity<JsonNode>> second) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{ready.countDown();go.await();return first.call();});
            var b=pool.submit(()->{ready.countDown();go.await();return second.call();});
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();
            return List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS));
        } finally { go.countDown();pool.shutdownNow(); }
    }
    @Test void concurrentFieldsAndResourceEditsHaveExactlyOneWinner() throws Exception {
        var body=snapshotResource(scanSnapshot());
        String id=http.postForObject("/api/v1/resources",body,JsonNode.class).path("data").path("resource_id").asText();
        var fieldResults=simultaneously(()->putField(id,"event_time",fieldEdit(1,"并发时间字段")),
                ()->putField(id,"container_no",fieldEdit(1,"并发箱号字段")));
        assertThat(fieldResults.stream().map(r->r.getStatusCode().value())).containsExactlyInAnyOrder(200,409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_field WHERE resource_id=? AND business_name IN ('并发时间字段','并发箱号字段')",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(2);
        body.put("version",2);body.put("owner_department","并发修改单位");
        var mixed=simultaneously(()->putResource(id,body),()->putField(id,"train_no",fieldEdit(2,"并发车次字段")));
        assertThat(mixed.stream().map(r->r.getStatusCode().value())).containsExactlyInAnyOrder(200,409);
        assertThat(db.queryForObject("SELECT owner_department FROM data_resource WHERE resource_id=?",String.class,id)).isEqualTo(
                mixed.get(0).getStatusCode().is2xxSuccessful()?"并发修改单位":"测试单位");
        assertThat(db.queryForObject("SELECT business_name FROM resource_field WHERE resource_id=? AND field_name='train_no'",String.class,id)).isEqualTo(
                mixed.get(1).getStatusCode().is2xxSuccessful()?"并发车次字段":"");
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(3);
    }
    @Test void concurrentPublishAndDeleteNeverLeavePublishedResourceWithoutFields() throws Exception {
        String id=http.postForObject("/api/v1/resources",snapshotResource(scanSnapshot()),JsonNode.class).path("data").path("resource_id").asText();
        var results=simultaneously(()->http.postForEntity("/api/v1/resources/"+id+"/publish",Map.of("version",1,"reason","测试资源发布"),JsonNode.class),
                ()->http.exchange("/api/v1/resources/"+id+"?version="+1,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class));
        assertThat(results.stream().filter(r->r.getStatusCode().is2xxSuccessful()).count()).isEqualTo(1);
        if(results.get(0).getStatusCode().is2xxSuccessful()) {
            assertThat(results.get(1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT status FROM data_resource WHERE resource_id=?",String.class,id)).isEqualTo("PUBLISHED");
            assertThat(resourceFields(id).size()).isEqualTo(12);
        } else {
            assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_field WHERE resource_id=?",Integer.class,id)).isZero();
        }
    }
    @Test void resourceCopiesSelectedOlderSnapshotAndPreservesImmutableProvenance() {
        String old=scanSnapshot(),newer=scanSnapshot();
        db.update("UPDATE metadata_scan_task SET finished_at=DATE_ADD(finished_at,INTERVAL 1 SECOND) WHERE task_id=?",newer);
        db.update("UPDATE metadata_scan_field SET column_type='varchar(64)',technical_comment='新扫描测试结构' WHERE task_id=? AND field_name='container_no'",newer);
        String expected=db.queryForObject("SELECT column_type FROM metadata_scan_field WHERE task_id=? AND field_name='container_no'",String.class,old);
        var body=snapshotResource(old);
        var response=http.postForEntity("/api/v1/resources",body,JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var resource=response.getBody().path("data");String id=resource.path("resource_id").asText();
        assertThat(resource.path("scan_task_id").asText()).isEqualTo(old);
        assertThat(db.queryForObject("SELECT scan_task_id FROM data_resource WHERE resource_id=?",String.class,id)).isEqualTo(old);
        assertThat(db.queryForObject("SELECT data_type FROM resource_field WHERE resource_id=? AND field_name='container_no'",String.class,id)).isEqualTo(expected);
        assertThat(http.getForObject("/api/v1/resources/"+id,JsonNode.class).path("data").path("scan_task_id").asText()).isEqualTo(old);
        body.put("version",1);body.put("scan_task_id",newer);
        assertThat(http.exchange("/api/v1/resources/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,id)).isEqualTo(1);
        // Existing clients may omit provenance on an ordinary edit; they can never rebind it.
        body.remove("scan_task_id");body.put("resource_name","只修改资源名称");
        assertThat(http.exchange("/api/v1/resources/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class).getBody().path("data").path("scan_task_id").asText()).isEqualTo(old);
        assertThat(http.exchange("/api/v1/scan-tasks/"+old,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/v1/scan-tasks/"+newer,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/api/v1/resources/"+id+"/fields",JsonNode.class).path("data").size()).isEqualTo(12);
    }
    @Test void resourceRejectsInvalidSnapshotOriginsAndRollsBackIncompleteFields() {
        String task=scanSnapshot();var valid=snapshotResource(task);
        for(String key:List.of("scan_task_id","source_id","schema_name","physical_table")) {
            var body=new java.util.HashMap<>(valid);
            body.put(key,switch(key){case "scan_task_id"->"SCAN_"+"a".repeat(32);case "source_id"->"SRC_"+"a".repeat(32);default->"not_scanned";});
            assertThat(http.postForEntity("/api/v1/resources",body,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var missing=new java.util.HashMap<>(valid);missing.remove("scan_task_id");
        assertThat(http.postForEntity("/api/v1/resources",missing,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for(String id:List.of("SCAN_missing","", "SCAN_"+"A".repeat(32))) {
            var body=new java.util.HashMap<>(valid);body.put("scan_task_id",id);
            assertThat(http.postForEntity("/api/v1/resources",body,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for(String status:List.of("PENDING","RUNNING","FAILED")) {
            db.update("UPDATE metadata_scan_task SET status=? WHERE task_id=?",status,task);
            assertThat(http.postForEntity("/api/v1/resources",valid,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        db.update("UPDATE metadata_scan_task SET status='SUCCESS' WHERE task_id=?",task);
        db.update("DELETE FROM metadata_scan_field WHERE task_id=? AND field_name='container_no'",task);
        assertThat(http.postForEntity("/api/v1/resources",valid,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM data_resource WHERE source_id=?",Integer.class,sourceId)).isZero();
        assertThat(http.exchange("/api/v1/scan-tasks/"+task,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/v1/resources",valid,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void concurrentRegistrationAndSnapshotDeletionCannotBothSucceed() throws Exception {
        String task=scanSnapshot();var body=snapshotResource(task);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var create=pool.submit(()->{start.await();return http.postForEntity("/api/v1/resources",body,JsonNode.class);});
            var delete=pool.submit(()->{start.await();return http.exchange("/api/v1/scan-tasks/"+task,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class);});
            start.countDown();var created=create.get(15,java.util.concurrent.TimeUnit.SECONDS);var deleted=delete.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(created.getStatusCode(),deleted.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.BAD_REQUEST);
            if(created.getStatusCode()==HttpStatus.OK) {
                String id=created.getBody().path("data").path("resource_id").asText();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_field WHERE resource_id=?",Integer.class,id)).isEqualTo(12);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM metadata_scan_field WHERE task_id=?",Integer.class,task)).isEqualTo(12);
            } else assertThat(db.queryForObject("SELECT COUNT(*) FROM data_resource WHERE source_id=?",Integer.class,sourceId)).isZero();
        } finally {pool.shutdownNow();}
    }

    @Test void deletionPreservesReferencedAndPublishedMetadata() {
        String freeTask=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText();
        assertThat(http.exchange("/api/v1/scan-tasks/"+freeTask,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/scan-tasks/"+freeTask,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String referencedTask=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText();
        var resourceBody=new java.util.HashMap<String,Object>(Map.of("resource_name","待删除草稿资源","source_id",sourceId,"scan_task_id",referencedTask,"schema_name",schema(),
                "physical_table","container_event","business_description","删除规则测试",
                "owner_department","数据管理部","security_level","内部","update_mode","实时"));
        String draftId=http.postForObject("/api/v1/resources",resourceBody,JsonNode.class).path("data").path("resource_id").asText();
        assertThat(http.exchange("/api/v1/scan-tasks/"+referencedTask,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/v1/resources/"+draftId+"?version="+db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,draftId),HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM resource_field WHERE resource_id=?",Integer.class,draftId)).isZero();
        assertThat(http.exchange("/api/v1/scan-tasks/"+referencedTask,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        resourceBody.put("scan_task_id",http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText());
        String publishedId=http.postForObject("/api/v1/resources",resourceBody,JsonNode.class).path("data").path("resource_id").asText();
        http.postForObject("/api/v1/resources/"+publishedId+"/publish",Map.of("version",db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,publishedId),"reason","测试资源发布"),JsonNode.class);
        assertThat(http.exchange("/api/v1/resources/"+publishedId+"?version="+db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,publishedId),HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var capability=Map.of("capability_name","待删除草稿能力","resource_id",publishedId,"type","REST_API",
                "http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE");
        String capabilityId=http.postForObject("/api/v1/capabilities",capability,JsonNode.class).path("data").path("capability_id").asText();
        assertThat(http.exchange("/api/v1/capabilities/"+capabilityId,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/capabilities/"+capabilityId,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void capabilityRegistryRequiresPublishedResourceAndDoesNotInventVerification() {
        String taskId=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText();
        var resourceBody=Map.of("resource_name","能力测试资源","source_id",sourceId,"scan_task_id",taskId,"schema_name",schema(),
                "physical_table","container_event","business_description","人工确认的事件含义",
                "owner_department","数据管理部","security_level","内部","update_mode","实时");
        String resourceId=http.postForObject("/api/v1/resources",resourceBody,JsonNode.class).path("data").path("resource_id").asText();
        var rest=Map.of("capability_name","事件查询接口","resource_id",resourceId,"type","REST_API",
                "http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE");
        assertThat(http.postForEntity("/api/v1/capabilities",rest,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.postForObject("/api/v1/resources/"+resourceId+"/publish",Map.of("version",db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,resourceId),"reason","测试资源发布"),JsonNode.class);
        var created=http.postForObject("/api/v1/capabilities",rest,JsonNode.class).path("data");
        String capId=created.path("capability_id").asText();
        assertThat(capId).startsWith("CAP_");
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("health_status").asText()).isEqualTo("UNKNOWN");
        var kafka=Map.of("capability_name","实时事件Topic","resource_id",resourceId,"type","KAFKA_TOPIC",
                "topic","port.container.event.v1","protocol","KAFKA","auth_type","NONE");
        var cdc=Map.of("capability_name","事件变化流","resource_id",resourceId,"type","CDC_STREAM",
                "topic","port.container.event.cdc","source_table","container_event","protocol","DEBEZIUM");
        var query=Map.of("capability_name","只读查询模板","resource_id",resourceId,"type","READONLY_QUERY",
                "query_template_code","QUERY_CONTAINER_EVENT");
        String kafkaId="";
        for (var body:List.of(kafka,cdc,query)) {
            var result=http.postForEntity("/api/v1/capabilities",body,JsonNode.class);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            if (body==kafka) kafkaId=result.getBody().path("data").path("capability_id").asText();
        }
        assertThat(http.getForObject("/api/v1/resources/"+resourceId+"/capabilities",JsonNode.class).path("data").size()).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM capability WHERE resource_id=?",Integer.class,resourceId)).isEqualTo(4);
        var kafkaVerified=http.postForEntity("/api/v1/capabilities/"+kafkaId+"/verify",null,JsonNode.class);
        assertThat(kafkaVerified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kafkaVerified.getBody().path("data").path("status").asText()).isEqualTo("VERIFIED");
        assertThat(kafkaVerified.getBody().path("data").path("health_status").asText()).isEqualTo("UP");
        assertThat(http.getForObject("/api/v1/capabilities/"+kafkaId+"/kafka-runtime",JsonNode.class).path("data").path("enabled").asBoolean()).isFalse();
        assertThat(http.postForEntity("/api/v1/capabilities/"+kafkaId+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/api/v1/capabilities/"+kafkaId+"/kafka-runtime",JsonNode.class).path("data").path("enabled").asBoolean()).isTrue();
        assertThat(http.getForObject("/api/v1/capabilities/"+kafkaId+"/runtime",JsonNode.class).path("data").path("enabled").asBoolean()).isFalse();
        http.postForObject("/api/v1/capabilities/"+kafkaId+"/disable",null,JsonNode.class);
        assertThat(http.getForObject("/api/v1/capabilities/"+kafkaId+"/kafka-runtime",JsonNode.class).path("data").path("enabled").asBoolean()).isFalse();
        var again=http.postForObject("/api/v1/capabilities/"+kafkaId+"/verify",null,JsonNode.class).path("data");
        assertThat(again.path("status").asText()).isEqualTo("DISABLED");
        db.update("UPDATE source_system SET status='DISABLED' WHERE source_id=?",sourceId);
        assertThat(http.postForEntity("/api/v1/capabilities/"+kafkaId+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        db.update("UPDATE source_system SET status='ENABLED' WHERE source_id=?",sourceId);
        var verified=http.postForEntity("/api/v1/capabilities/"+capId+"/verify",null,JsonNode.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().path("data").path("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.getBody().path("data").path("health_status").asText()).isEqualTo("UP");
        assertThat(http.postForEntity("/api/v1/capabilities/"+capId+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/api/v1/capabilities/"+capId+"/runtime",JsonNode.class).path("data").path("enabled").asBoolean()).isTrue();
        http.postForObject("/api/v1/capabilities/"+capId+"/disable",null,JsonNode.class);
        providerReady.set(false);
        var down=http.postForEntity("/api/v1/capabilities/"+capId+"/verify",null,JsonNode.class).getBody().path("data");
        assertThat(down.path("status").asText()).isEqualTo("DISABLED");
        assertThat(down.path("health_status").asText()).isEqualTo("DOWN");
        assertThat(http.postForEntity("/api/v1/capabilities/"+capId+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/v1/capabilities",rest,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var badRest=Map.of("capability_name","恶意外部地址","resource_id",resourceId,"type","REST_API",
                "http_method","GET","endpoint","https://example.org/private","protocol","HTTP","auth_type","NONE");
        assertThat(http.postForEntity("/api/v1/capabilities",badRest,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        providerReady.set(true);
        db.update("UPDATE source_system SET connection_env_prefix='SOURCE_OTHER' WHERE source_id=?",sourceId);
        assertThat(http.postForEntity("/api/v1/capabilities/"+capId+"/verify",null,JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForObject("/api/v1/capabilities/"+capId,JsonNode.class).path("data").path("health_status").asText())
                .isEqualTo("DOWN");
    }

    private String publishedRest() {
        String taskId=http.postForObject("/api/v1/sources/"+sourceId+"/scan",null,JsonNode.class).path("data").path("task_id").asText();
        var resource=Map.of("resource_name","REST启停测试资源","source_id",sourceId,"scan_task_id",taskId,"schema_name",schema(),
                "physical_table","container_event","business_description","用于验证真实接口状态",
                "owner_department","测试单位","security_level","内部","update_mode","实时");
        String resourceId=http.postForObject("/api/v1/resources",resource,JsonNode.class).path("data").path("resource_id").asText();
        http.postForObject("/api/v1/resources/"+resourceId+"/publish",Map.of("version",db.queryForObject("SELECT version FROM data_resource WHERE resource_id=?",Integer.class,resourceId),"reason","测试资源发布"),JsonNode.class);
        return http.postForObject("/api/v1/capabilities",Map.of("capability_name","REST启停查询","resource_id",resourceId,
                "type","REST_API","http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE"),JsonNode.class)
                .path("data").path("capability_id").asText();
    }
    private JsonNode operation(String id,String name) {
        var response=http.postForEntity("/api/v1/capabilities/"+id+"/"+name,null,JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data");
    }
    private JsonNode runtime(String id) {
        return http.getForObject("/api/v1/capabilities/"+id+"/runtime",JsonNode.class).path("data");
    }

    @Test void invocationShowsConfiguredAddressAndHeaderWithoutEnablingOrProbing() {
        String id=publishedRest();
        Runnable unexpectedProbe=()->{ throw new AssertionError("Calling instructions must not probe Provider"); };
        onProbe.set(unexpectedProbe);
        var guide=http.getForObject("/api/v1/capabilities/"+id+"/invocation",JsonNode.class).path("data");
        assertThat(guide.path("provider_base_url").asText()).isEqualTo("http://127.0.0.1:"+provider.getAddress().getPort()+"/");
        assertThat(guide.path("request_url").asText()).isEqualTo(guide.path("provider_base_url").asText()+"containers/events");
        assertThat(guide.path("http_method").asText()).isEqualTo("GET");
        assertThat(guide.path("header_name").asText()).isEqualTo("X-Capability-Id");
        assertThat(guide.path("header_value").asText()).isEqualTo(id);
        assertThat(guide.path("required_headers").toString()).isEqualTo("[\"Authorization\",\"X-Relationship-Id\",\"X-Capability-Id\"]");
        assertThat(guide.path("authorization_note").asText()).contains("有效需求方调用令牌","共享关系");
        assertThat(guide.path("enabled").asBoolean()).isFalse();
        assertThat(guide.toString()).doesNotContain("password","credential_ref","container_no","username");
        assertThat(onProbe.getAndSet(null)).isSameAs(unexpectedProbe);
        assertThat(http.getForObject("/api/v1/capabilities/"+id,JsonNode.class).path("data").path("version").asInt()).isEqualTo(1);
        assertThat(http.getForEntity("/api/v1/capabilities/CAP_missing/invocation",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        operation(id,"verify");
        operation(id,"enable");
        assertThat(http.getForObject("/api/v1/capabilities/"+id+"/invocation",JsonNode.class).path("data").path("enabled").asBoolean()).isTrue();
        operation(id,"disable");
        assertThat(http.getForObject("/api/v1/capabilities/"+id+"/invocation",JsonNode.class).path("data").path("enabled").asBoolean()).isFalse();
        var resourceId=http.getForObject("/api/v1/capabilities/"+id,JsonNode.class).path("data").path("resource_id").asText();
        var kafka=http.postForObject("/api/v1/capabilities",Map.of("capability_name","调用说明不支持的消息能力","resource_id",resourceId,
                "type","KAFKA_TOPIC","topic","port.container.event.v1","protocol","KAFKA","auth_type","NONE"),JsonNode.class).path("data").path("capability_id").asText();
        assertThat(http.getForEntity("/api/v1/capabilities/"+kafka+"/invocation",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void dynamicSourceRestLifecycleRechecksHealthAndRespectsSourceState() {
        var dynamic=Map.of("source_name","动态REST启停来源","source_type","MYSQL","host",uri().getHost(),"port",uri().getPort(),
                "database_name",schema(),"username",System.getenv("TEST_PROVIDER_SOURCE_DB_USER"),
                "password",System.getenv("TEST_PROVIDER_SOURCE_DB_PASSWORD"),"ssl_mode","DISABLED");
        assertThat(http.exchange("/api/v1/sources/"+sourceId,HttpMethod.PUT,new HttpEntity<>(dynamic),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sources.selectById(sourceId).connectionEnvPrefix).isNull();
        String id=publishedRest();
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/disable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        providerRuntimeReady.set(false);
        assertThat(operation(id,"verify").path("health_status").asText()).isEqualTo("DOWN");
        providerRuntimeReady.set(true);
        providerAccessReady.set(false);
        assertThat(operation(id,"verify").path("health_status").asText()).isEqualTo("DOWN");
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        providerAccessReady.set(true);
        operation(id,"verify");
        providerReady.set(false);
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var stale=http.getForObject("/api/v1/capabilities/"+id,JsonNode.class).path("data");
        assertThat(stale.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(stale.path("health_status").asText()).isEqualTo("DOWN");
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
        providerReady.set(true);
        operation(id,"verify");
        int enabledVersion=operation(id,"enable").path("version").asInt();
        assertThat(operation(id,"enable").path("version").asInt()).isEqualTo(enabledVersion);
        assertThat(runtime(id).path("enabled").asBoolean()).isTrue();
        assertThat(runtime(id).path("database_name").asText()).isEqualTo(schema());
        assertThat(runtime(id).toString()).doesNotContain("password","encrypted_value","credential_ref","container_no");
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/verify",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.exchange("/api/v1/sources/"+sourceId+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status","DISABLED")),JsonNode.class);
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
        assertThat(runtime(id).path("reason").asText()).contains("数据源已停用");
        int disabledVersion=operation(id,"disable").path("version").asInt();
        assertThat(operation(id,"disable").path("version").asInt()).isEqualTo(disabledVersion);
        assertThat(http.postForEntity("/api/v1/capabilities/"+id+"/enable",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.exchange("/api/v1/sources/"+sourceId+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status","ENABLED")),JsonNode.class);
        assertThat(operation(id,"verify").path("status").asText()).isEqualTo("DISABLED");
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
        operation(id,"enable");
        db.update("UPDATE source_system SET port=port+1 WHERE source_id=?",sourceId);
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
        assertThat(runtime(id).path("reason").asText()).contains("Provider来源不一致");
    }

    @Test void concurrentStateChangeCannotBeOverwrittenByAnEnableProbe() {
        String id=publishedRest();
        operation(id,"verify");
        onProbe.set(()->db.update("UPDATE capability SET status='DISABLED',version=version+1 WHERE capability_id=?",id));
        var response=http.postForEntity("/api/v1/capabilities/"+id+"/enable",null,JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("message").asText()).contains("状态已变化");
        assertThat(http.getForObject("/api/v1/capabilities/"+id,JsonNode.class).path("data").path("status").asText()).isEqualTo("DISABLED");
        assertThat(runtime(id).path("enabled").asBoolean()).isFalse();
    }
}
