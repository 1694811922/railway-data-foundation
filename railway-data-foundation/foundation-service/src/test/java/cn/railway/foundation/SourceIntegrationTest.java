package cn.railway.foundation;

import cn.railway.foundation.metadata.*;
import cn.railway.foundation.source.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class SourceIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired SourceMapper mapper;
    @Autowired MetadataAdapterFactory adapters;
    final List<String> createdIds = new ArrayList<>();

    static URI sourceUri() { return URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5)); }
    static String schema() { return sourceUri().getPath().substring(1); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        for (String prefix : List.of("SOURCE_TEST", "SOURCE_DENIED")) {
            properties.add(prefix+"_HOST", () -> sourceUri().getHost());
            properties.add(prefix+"_PORT", () -> sourceUri().getPort());
            properties.add(prefix+"_DATABASE", SourceIntegrationTest::schema);
            properties.add(prefix+"_USER", () -> prefix.equals("SOURCE_DENIED") ? "nonexistent_foundation_test_user" : System.getenv("TEST_SOURCE_DB_USER"));
            properties.add(prefix+"_PASSWORD", () -> System.getenv("TEST_SOURCE_DB_PASSWORD"));
            properties.add(prefix+"_SSL_MODE", () -> "DISABLED");
        }
    }

    @BeforeAll static void migrateSource() {
        Flyway.configure().dataSource(System.getenv("TEST_SOURCE_DB_URL"), System.getenv("TEST_SOURCE_DB_USER"), System.getenv("TEST_SOURCE_DB_PASSWORD"))
                .locations("filesystem:"+Path.of("..","sql","source").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
    }
    @AfterEach void cleanRegisteredSources() { createdIds.forEach(mapper::deleteById); }

    Map<String,String> request(String prefix) {
        return Map.of("source_name","广州港测试数据源","source_type","MYSQL","connection_env_prefix",prefix,"description","集成测试");
    }
    String create(String prefix) {
        var response = http.postForEntity("/api/v1/sources", request(prefix), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = response.getBody().path("data").path("source_id").asText();
        createdIds.add(id);
        assertThat(id).matches("SRC_[a-f0-9]{32}");
        return id;
    }

    @Test void crudPersistsOnlyEnvironmentReferenceAndPreservesIdentity() {
        String id = create("SOURCE_TEST");
        var get = http.getForObject("/api/v1/sources/"+id, JsonNode.class);
        assertThat(get.path("data").path("source_name").asText()).isEqualTo("广州港测试数据源");
        assertThat(get.toString()).doesNotContain(System.getenv("TEST_SOURCE_DB_PASSWORD"), "password");
        assertThat(mapper.selectById(id).connectionEnvPrefix).isEqualTo("SOURCE_TEST");
        var updated = http.exchange("/api/v1/sources/"+id, HttpMethod.PUT,
                new HttpEntity<>(Map.of("source_name","更新后名称","source_type","MYSQL","connection_env_prefix","SOURCE_TEST")),JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().path("data").path("source_id").asText()).isEqualTo(id);
        assertThat(updated.getBody().path("data").path("source_name").asText()).isEqualTo("更新后名称");
        assertThat(updated.getBody().path("data").path("created_at")).isEqualTo(get.path("data").path("created_at"));
        var list = http.getForObject("/api/v1/sources?limit=200",JsonNode.class);
        assertThat(list.path("data").toString()).contains(id).doesNotContain("password");
    }

    @Test void realConnectionReturnsProductVersionAndTiming() {
        String id = create("SOURCE_TEST");
        var result = http.postForEntity("/api/v1/sources/"+id+"/test",null,JsonNode.class);
        var data=result.getBody().path("data");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data.path("connected").asBoolean()).isTrue();
        assertThat(data.path("database_product").asText()).isEqualTo("MySQL");
        assertThat(data.path("database_version").asText()).startsWith("8.");
        assertThat(data.path("response_time_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(data.toString()).doesNotContain(System.getenv("TEST_SOURCE_DB_PASSWORD"));
    }

    @Test void invalidCredentialsProduceSafeNegativeResult() {
        String id=create("SOURCE_DENIED");
        var result=http.postForObject("/api/v1/sources/"+id+"/test",null,JsonNode.class);
        assertThat(result.path("data").path("connected").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain("nonexistent_foundation_test_user",System.getenv("TEST_SOURCE_DB_PASSWORD"));
    }

    @Test void adapterScansOnlyTechnicalMetadataAndBindsTableNames() {
        var adapter=adapters.create(mapper.selectById(create("SOURCE_TEST")));
        assertThat(adapter.testConnection()).isTrue();
        var tables=adapter.scanTables();
        assertThat(tables).extracting(TableMetadata::tableName).contains("container_event").doesNotContain("flyway_schema_history");
        assertThat(tables).allSatisfy(table -> assertThat(table.schema()).isEqualTo(schema()));
        var fields=adapter.scanFields(schema(),"container_event");
        assertThat(fields).hasSize(12);
        assertThat(fields.get(0).columnName()).isEqualTo("id");
        assertThat(fields.get(0).primaryKey()).isTrue();
        assertThat(fields.get(0).nullable()).isFalse();
        assertThat(fields).allSatisfy(field -> assertThat(field.columnComment()).isNotBlank());
        assertThat(fields).extracting(FieldMetadata::ordinalPosition).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12);
        assertThat(adapter.scanFields(schema(),"x' OR '1'='1")).isEmpty();
        assertThatThrownBy(() -> adapter.scanFields("information_schema","COLUMNS")).hasMessage("只能扫描当前数据源中的表");
    }

    @Test void validationRejectsUnsupportedTypesSecretsAndMissingConfiguration() {
        for (Object body : List.of(
                Map.of("source_name","","source_type","MYSQL","connection_env_prefix","SOURCE_TEST"),
                Map.of("source_name","demo","source_type","KAFKA","connection_env_prefix","SOURCE_TEST"),
                Map.of("source_name","demo","source_type","MYSQL","connection_env_prefix","FOUNDATION_DB"),
                Map.of("source_name","demo","source_type","MYSQL","connection_env_prefix","SOURCE_MISSING"),
                Map.of("source_name","demo","source_type","MYSQL","connection_env_prefix","SOURCE_TEST","password","must-not-be-accepted"))) {
            var response=http.postForEntity("/api/v1/sources",body,JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("code").asInt()).isEqualTo(10001);
            assertThat(response.getBody().toString()).doesNotContain("must-not-be-accepted");
        }
    }

    @Test void missingSourcesAndInvalidPaginationUseUnifiedErrors() {
        assertThat(http.getForEntity("/api/v1/sources/SRC_missing",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/v1/sources/SRC_missing/test",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/api/v1/sources/SRC_missing",HttpMethod.PUT,new HttpEntity<>(request("SOURCE_TEST")),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String query : List.of("limit=0","limit=201","offset=-1","offset=1000001","limit=abc")) {
            assertThat(http.getForEntity("/api/v1/sources?"+query,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
