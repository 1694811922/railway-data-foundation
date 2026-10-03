package cn.railway.foundation;

import cn.railway.foundation.source.*;
import cn.railway.foundation.metadata.*;
import cn.railway.foundation.credential.*;
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
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DynamicSourceIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired SourceMapper sources;
    @Autowired SourceConnectionFactory connections;
    @Autowired CredentialService credentials;
    final List<String> ids=new ArrayList<>();
    static URI uri(String variable) { return URI.create(System.getenv(variable).substring(5)); }
    static String schema(String variable) { return uri(variable).getPath().substring(1); }
    static String password() { return System.getenv("TEST_PROVIDER_SOURCE_DB_PASSWORD"); }
    @DynamicPropertySource static void legacyConfiguration(DynamicPropertyRegistry properties) {
        properties.add("SOURCE_LEGACY_TEST_HOST",()->uri("TEST_SOURCE_DB_URL").getHost());
        properties.add("SOURCE_LEGACY_TEST_PORT",()->uri("TEST_SOURCE_DB_URL").getPort());
        properties.add("SOURCE_LEGACY_TEST_DATABASE",()->schema("TEST_SOURCE_DB_URL"));
        properties.add("SOURCE_LEGACY_TEST_USER",()->System.getenv("TEST_PROVIDER_SOURCE_DB_USER"));
        properties.add("SOURCE_LEGACY_TEST_PASSWORD",DynamicSourceIntegrationTest::password);
        properties.add("SOURCE_LEGACY_TEST_SSL_MODE",()->"DISABLED");
    }

    @BeforeAll static void fixtures() throws Exception {
        Flyway.configure().dataSource(System.getenv("TEST_SOURCE_DB_URL"),System.getenv("TEST_SOURCE_DB_USER"),System.getenv("TEST_SOURCE_DB_PASSWORD"))
                .locations("filesystem:"+Path.of("..","sql","source").toAbsolutePath().normalize()).cleanDisabled(true).load().migrate();
        if (!schema("TEST_SECOND_SOURCE_DB_URL").equals("another_source_test")) throw new IllegalStateException("Only dedicated test schema allowed");
        try(var connection=DriverManager.getConnection(System.getenv("TEST_SECOND_SOURCE_DB_URL"),System.getenv("TEST_SOURCE_DB_USER"),System.getenv("TEST_SOURCE_DB_PASSWORD"));
                var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS train_dispatch(id BIGINT NOT NULL PRIMARY KEY COMMENT '调度记录编号', train_no VARCHAR(30) NOT NULL COMMENT '班列编号', departure_time DATETIME NOT NULL COMMENT '发车时间') COMMENT='铁路调度测试表'");
        }
    }
    @AfterEach void cleanup() {
        for (String id:ids) {
            String ref=sources.selectById(id).credentialRef;
            db.update("DELETE FROM metadata_scan_field WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",id);
            db.update("DELETE FROM metadata_scan_table WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",id);
            db.update("DELETE FROM metadata_scan_task WHERE source_id=?",id);
            sources.deleteById(id); if(ref!=null)credentials.deleteCredential(ref);
        }
    }
    Map<String,Object> request(String name,String variable) {
        var body=new HashMap<String,Object>();var uri=uri(variable);
        body.put("source_name",name);body.put("source_type","MYSQL");body.put("host",uri.getHost());body.put("port",uri.getPort());
        body.put("database_name",schema(variable));body.put("username",System.getenv("TEST_PROVIDER_SOURCE_DB_USER"));
        body.put("password",password());body.put("ssl_mode","DISABLED");body.put("description","动态接入集成测试");
        return body;
    }
    String create(Map<String,Object> body) {
        var response=http.postForEntity("/api/v1/sources",body,JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data=response.getBody().path("data");String id=data.path("source_id").asText();ids.add(id);
        assertThat(data.path("connection_mode").asText()).isEqualTo("DYNAMIC");
        assertThat(data.has("password")).isFalse();assertThat(data.toString().contains(password())).isFalse();
        return id;
    }
    JsonNode scan(String id) {
        var task=http.postForObject("/api/v1/sources/"+id+"/scan",null,JsonNode.class).path("data");
        assertThat(task.path("status").asText()).isEqualTo("SUCCESS");return task;
    }
    JsonNode tables(JsonNode task) { return http.getForObject("/api/v1/scan-tasks/"+task.path("task_id").asText()+"/tables",JsonNode.class).path("data"); }
    @Test void twoIndependentDatabasesAndThirdRegistrationScanWithoutEnvironmentGroups() throws Exception {
        String port=create(request("广州港测试业务库","TEST_SOURCE_DB_URL"));
        String dispatch=create(request("铁路调度测试业务库","TEST_SECOND_SOURCE_DB_URL"));
        assertThat(sources.selectById(port).connectionEnvPrefix).isNull();
        assertThat(sources.selectById(dispatch).databaseName).isEqualTo("another_source_test");
        var portTask=scan(port);var dispatchTask=scan(dispatch);
        assertThat(tables(portTask).toString()).contains("container_event").doesNotContain("train_dispatch");
        assertThat(tables(dispatchTask).toString()).contains("train_dispatch").doesNotContain("container_event");
        assertThat(portTask.path("field_count").asInt()).isEqualTo(12);assertThat(dispatchTask.path("field_count").asInt()).isEqualTo(3);
        var fields=http.getForObject("/api/v1/scan-tasks/"+portTask.path("task_id").asText()+"/tables/container_event/fields",JsonNode.class).path("data");
        assertThat(fields.size()).isEqualTo(12);assertThat(fields.toString()).contains("container_no","event_time");
        assertThat(fields.get(0).path("field_name").asText()).isEqualTo("id");assertThat(fields.get(0).path("primary_key").asBoolean()).isTrue();
        assertThat(http.getForEntity("/api/v1/scan-tasks/"+dispatchTask.path("task_id").asText()+"/tables/container_event/fields",JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        String third=create(request("第三个测试接入","TEST_SECOND_SOURCE_DB_URL"));assertThat(tables(scan(third)).toString()).contains("train_dispatch");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('container_event','train_dispatch')",Integer.class)).isZero();
        var connection=connections.openConnection(port);assertThat(connection.isReadOnly()).isTrue();connection.close();assertThat(connection.isClosed()).isTrue();
        var adapter=new MysqlMetadataAdapter(sources.selectById(port),schema("TEST_SOURCE_DB_URL"),connections);
        assertThatThrownBy(()->adapter.scanFields("another_source_test","train_dispatch")).hasMessageContaining("只能扫描当前数据源");
    }
    @Test void encryptedPasswordsAreNotReturnedBlankEditPreservesSecretAndRotationChangesOnlyOwnedCredential() {
        var body=request("加密凭据测试","TEST_SOURCE_DB_URL");String id=create(body);
        String ref=sources.selectById(id).credentialRef;
        String ciphertext=db.queryForObject("SELECT encrypted_value FROM source_credential WHERE credential_ref=?",String.class,ref);
        assertThat(ciphertext.contains(password())).isFalse();assertThat(ciphertext).startsWith("v1:");
        body.put("source_name","编辑后名称");body.put("password","");
        var updated=http.exchange("/api/v1/sources/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT encrypted_value FROM source_credential WHERE credential_ref=?",String.class,ref)).isEqualTo(ciphertext);
        assertThat(http.postForObject("/api/v1/sources/"+id+"/test",null,JsonNode.class).path("data").path("connected").asBoolean()).isTrue();
        body.put("password","wrong-password-for-test");
        assertThat(http.exchange("/api/v1/sources/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sources.selectById(id).credentialRef).isEqualTo(ref);
        var failed=http.postForObject("/api/v1/sources/"+id+"/test",null,JsonNode.class).path("data");
        assertThat(failed.path("connected").asBoolean()).isFalse();assertThat(failed.path("reason").asText()).isEqualTo("用户名或密码错误");
        assertThat(failed.path("error_code").asText()).isEqualTo("SOURCE_CONNECTION_FAILED");
        assertThat(failed.toString()).doesNotContain("wrong-password-for-test","SQLException");
        var failedTask=http.postForObject("/api/v1/sources/"+id+"/scan",null,JsonNode.class).path("data");
        assertThat(failedTask.path("status").asText()).isEqualTo("FAILED");assertThat(failedTask.path("failure_reason").asText()).contains("用户名或密码错误");
        assertThat(tables(failedTask).size()).isZero();
        assertThat(http.getForObject("/api/v1/sources/"+id,JsonNode.class).path("data").has("password")).isFalse();
    }
    @Test void disabledSourcesRejectConnectionsAndScansUntilReenabled() {
        String id=create(request("启停测试","TEST_SOURCE_DB_URL"));
        var disabled=http.exchange("/api/v1/sources/"+id+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status","DISABLED")),JsonNode.class);
        assertThat(disabled.getBody().path("data").path("status").asText()).isEqualTo("DISABLED");
        assertThat(http.postForEntity("/api/v1/sources/"+id+"/test",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/v1/sources/"+id+"/scan",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM metadata_scan_task WHERE source_id=?",Integer.class,id)).isZero();
        http.exchange("/api/v1/sources/"+id+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status","ENABLED")),JsonNode.class);
        scan(id);
    }
    @Test void legacyRegistrationCanConvertWithoutChangingSourceIdentity() {
        var legacy=http.postForEntity("/api/v1/sources",Map.of("source_name","旧配置转换","source_type","MYSQL","connection_env_prefix","SOURCE_LEGACY_TEST"),JsonNode.class);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id=legacy.getBody().path("data").path("source_id").asText();ids.add(id);
        var converted=http.exchange("/api/v1/sources/"+id,HttpMethod.PUT,new HttpEntity<>(request("转换后配置","TEST_SOURCE_DB_URL")),JsonNode.class);
        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(converted.getBody().path("data").path("source_id").asText()).isEqualTo(id);
        assertThat(sources.selectById(id).connectionEnvPrefix).isNull();scan(id);
    }
    @Test void validatesConfigurationAndDoesNotOrphanCredentialsOnRejectedRequests() {
        int before=db.queryForObject("SELECT COUNT(*) FROM source_credential",Integer.class);
        for (String field:List.of("password","host","port","database_name","username")) {
            var body=request("缺少配置","TEST_SOURCE_DB_URL");body.remove(field);
            assertThat(http.postForEntity("/api/v1/sources",body,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (var change:List.of(Map.of("host","localhost?allowMultiQueries=true"),Map.of("port",0),Map.of("database_name","db/x"),
                Map.of("source_type","ORACLE"),Map.of("credential_ref","CRED чужой"),Map.of("jdbc_url","jdbc:mysql://localhost/db"))) {
            var body=request("非法配置","TEST_SOURCE_DB_URL");body.putAll(change);
            var response=http.postForEntity("/api/v1/sources",body,JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody().toString().contains(password())).isFalse();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM source_credential",Integer.class)).isEqualTo(before);
        var body=request("未连通仍可登记","TEST_SOURCE_DB_URL");body.put("port",1);String id=create(body);
        var failure=http.postForObject("/api/v1/sources/"+id+"/test",null,JsonNode.class).path("data");
        assertThat(failure.path("connected").asBoolean()).isFalse();assertThat(failure.path("reason").asText()).contains("地址");
    }
}
