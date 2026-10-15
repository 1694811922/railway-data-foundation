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

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubjectIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    final List<String> ids = new ArrayList<>();
    final String code = "TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    @AfterEach void cleanup() { ids.forEach(id -> jdbc.update("DELETE FROM sharing_subject WHERE subject_id=? AND subject_code LIKE 'TEST_%'", id)); }
    Map<String,Object> createRequest(String type) {
        return Map.of("subject_code",code,"subject_name","测试共享主体","subject_type",type,"owner_department","测试责任单位","description","仅集成测试登记");
    }
    JsonNode create(String type) {
        var r = http.postForEntity("/api/v1/subjects", createRequest(type), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = r.getBody().path("data"); ids.add(data.path("subject_id").asText());
        assertThat(ids.get(ids.size()-1)).matches("SUB_[a-f0-9]{32}");
        return data;
    }
    Map<String,Object> updateRequest(int version, String name) {
        return Map.of("subject_name",name,"owner_department","更新单位","description","更新说明","version",version);
    }
    ResponseEntity<JsonNode> update(String id, Map<String,Object> body) {
        return http.exchange("/api/v1/subjects/"+id,HttpMethod.PUT,new HttpEntity<>(body),JsonNode.class);
    }
    ResponseEntity<JsonNode> status(String id, String status, int version) {
        return http.postForEntity("/api/v1/subjects/"+id+"/status",Map.of("status",status,"version",version),JsonNode.class);
    }
    @Test void createUpdateReloadPreservesBusinessIdentityAndTimes() {
        var first=create("PROVIDER"); String id=first.path("subject_id").asText();
        assertThat(first.path("status").asText()).isEqualTo("ENABLED");
        assertThat(first.path("version").asInt()).isEqualTo(1);
        var r=update(id,updateRequest(1,"修改主体")); assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stored=http.getForObject("/api/v1/subjects/"+id,JsonNode.class).path("data");
        assertThat(stored.path("subject_name").asText()).isEqualTo("修改主体");
        assertThat(stored.path("subject_code")).isEqualTo(first.path("subject_code"));
        assertThat(stored.path("subject_type")).isEqualTo(first.path("subject_type"));
        assertThat(stored.path("created_at")).isEqualTo(first.path("created_at"));
        assertThat(stored.path("version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT subject_name FROM sharing_subject WHERE subject_id=?",String.class,id)).isEqualTo("修改主体");
        assertThat(stored.toString()).doesNotContain("password","token","credential");
    }
    @Test void duplicateCodesAndClientControlledIdentityAreRejected() {
        var first=create("DEMAND"); String id=first.path("subject_id").asText();
        assertThat(http.postForEntity("/api/v1/subjects",createRequest("PROVIDER"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String forbidden:List.of("subject_code","subject_type","status","subject_id")) {
            var request=new HashMap<>(updateRequest(1,"非法修改")); request.put(forbidden,"PROVIDER");
            assertThat(update(id,request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM sharing_subject WHERE subject_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void statusRequiresCurrentVersionAndDoesNotGrantDataAccess() {
        int capabilityCount=jdbc.queryForObject("SELECT COUNT(*) FROM capability",Integer.class);
        var data=create("DEMAND"); String id=data.path("subject_id").asText();
        assertThat(status(id,"DISABLED",1).getBody().path("data").path("version").asInt()).isEqualTo(2);
        assertThat(status(id,"ENABLED",1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(id,"DISABLED",2).getBody().path("data").path("version").asInt()).isEqualTo(2);
        assertThat(status(id,"ENABLED",2).getBody().path("data").path("version").asInt()).isEqualTo(3);
        assertThat(update(id,updateRequest(2,"旧表单")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capability",Integer.class)).isEqualTo(capabilityCount);
    }
    @Test void invalidInputUnknownPropertiesAndMissingVersionsCannotWrite() {
        for(var entry:List.of(Map.entry("subject_code","lowercase"),Map.entry("subject_name","  "),Map.entry("subject_type","ADMIN"),Map.entry("owner_department",""),Map.entry("description","x".repeat(1001)),Map.entry("password","secret"))) {
            var body=new HashMap<>(createRequest("PROVIDER"));body.put(entry.getKey(),entry.getValue());
            assertThat(http.postForEntity("/api/v1/subjects",body,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var data=create("PROVIDER");String id=data.path("subject_id").asText();
        var body=new HashMap<>(updateRequest(1,"修改"));body.remove("version");
        assertThat(update(id,body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(id,"RETIRED",1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(id,"DISABLED",0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void boundedPaginationAndNotFoundAreExplicit() {
        String id=create("PROVIDER").path("subject_id").asText();
        assertThat(http.getForObject("/api/v1/subjects?limit=200",JsonNode.class).path("data").toString()).contains(id);
        assertThat(http.getForObject("/api/v1/subjects?limit=1",JsonNode.class).path("data").size()).isEqualTo(1);
        for(String params:List.of("limit=0","limit=201","offset=-1","offset=1000001"))
            assertThat(http.getForEntity("/api/v1/subjects?"+params,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/subjects/SUB_MISSING",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(update("SUB_MISSING",updateRequest(1,"修改")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void deletionRequiresDisabledSubject() {
        var data=create("PROVIDER");String id=data.path("subject_id").asText();
        assertThat(http.exchange("/api/v1/subjects/"+id,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(id,"DISABLED",1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/v1/subjects/"+id,HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/subjects/"+id,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void concurrentOldFormWritesHaveExactlyOneWinner() throws Exception {
        String id=create("DEMAND").path("subject_id").asText();
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{gate.await();return update(id,updateRequest(1,"并发一")).getStatusCode();});
            var b=executor.submit(()->{gate.await();return update(id,updateRequest(1,"并发二")).getStatusCode();});
            gate.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.BAD_REQUEST);
            assertThat(jdbc.queryForObject("SELECT version FROM sharing_subject WHERE subject_id=?",Integer.class,id)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
}
