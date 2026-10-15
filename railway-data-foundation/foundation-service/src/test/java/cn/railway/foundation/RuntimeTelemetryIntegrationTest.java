package cn.railway.foundation;

import cn.railway.foundation.telemetry.RuntimeReportRequest;
import cn.railway.foundation.telemetry.RuntimeTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL tests: transaction rollback, replay races, time windows and summary-only contract. */
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeTelemetryIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RuntimeTelemetryService service;
    static final String CONTROL="test_runtime_"+"r".repeat(43);
    static final String NODE="test-runtime";
    final List<String> reports=new ArrayList<>();
    Instant start;
    String resource,capability,subject,relationship,policy;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){p.add("PROVIDER_CONTROL_TOKEN",()->CONTROL);p.add("PROVIDER_TELEMETRY_NODE_ID",()->NODE);p.add("FOUNDATION_KAFKA_TOPIC",()->"port.container.event.v1");}
    String uuid(){return UUID.randomUUID().toString();}
    String id(String prefix){return prefix+"_"+uuid().replace("-","");}
    @BeforeEach void setup(){
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("foundation_test");
        start=Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(120);
        resource=id("RES");capability=id("CAP");subject=id("SUB");relationship=id("REL");policy=id("POL");
    }
    @AfterEach void cleanup(){for(String id:reports){db.update("DELETE FROM sharing_runtime_event WHERE report_id=? AND collector_id=?",id,NODE);db.update("DELETE FROM sharing_runtime_report WHERE report_id=? AND collector_id=?",id,NODE);}}
    Map<String,Object> event(){
        var e=new LinkedHashMap<String,Object>();e.put("event_id",uuid());e.put("trace_id",uuid());
        e.put("relationship_id",relationship);e.put("capability_id",capability);e.put("subject_id",subject);e.put("resource_id",resource);e.put("policy_id",policy);
        e.put("relationship_version",4);e.put("policy_version",1);e.put("started_at",start.plusMillis(1000).toString());e.put("completed_at",start.plusMillis(1030).toString());
        e.put("outcome","SUCCESS");e.put("reason_code","COMPLETED");e.put("http_status",200);e.put("record_count",2);e.put("byte_count",512);e.put("duration_ms",30);return e;
    }
    Map<String,Object> report(List<Map<String,Object>> events){
        String id=uuid();reports.add(id);var r=new LinkedHashMap<String,Object>();r.put("report_id",id);r.put("collection_point","REST_PROVIDER");
        r.put("window_start",start.toString());r.put("window_until",start.plusSeconds(60).toString());r.put("window_closed",false);r.put("events",events);return r;
    }
    ResponseEntity<JsonNode> post(Map<String,Object> body,String control){
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(control!=null)h.set("X-Provider-Token",control);
        return http.exchange("/api/v1/telemetry/reports",HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);
    }
    JsonNode success(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(r.getBody().path("code").asInt()).isZero();return r.getBody().path("data");}
    void rejected(Map<String,Object> r){assertThat(post(r,CONTROL).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
    long countEvents(){return db.queryForObject("SELECT COUNT(*) FROM sharing_runtime_event WHERE resource_id=?",Long.class,resource);}
    JsonNode windows(String point,String suffix){return success(http.getForEntity("/api/v1/metrics/windows?collectionPoint="+point+"&from="+start+"&until="+start.plusSeconds(180)+suffix,JsonNode.class));}
    Map<String,Object> denied(){var e=event();e.put("outcome","DENIED");e.put("reason_code","FIELDS_FORBIDDEN");e.put("http_status",403);e.put("record_count",0);e.put("byte_count",0);return e;}

    @Test void serviceCredentialIsRequiredAndUntrustedPayloadFieldsNeverPersist(){
        var r=report(List.of(event()));assertThat(post(r,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(r,"rdf_"+"x".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String field:List.of("collector_id","subject_id","token","password","business_rows")){
            var unsafe=new LinkedHashMap<>(r);unsafe.put(field,"SECRET_MARKER");var response=post(unsafe,CONTROL);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody().toString()).doesNotContain("SECRET_MARKER");
        }
        var e=event();e.put("business_rows",List.of(Map.of("container_no","SECRET_MARKER")));rejected(report(List.of(e)));assertThat(countEvents()).isZero();
        success(post(r,CONTROL));assertThat(db.queryForObject("SELECT collector_id FROM sharing_runtime_report WHERE report_id=?",String.class,r.get("report_id"))).isEqualTo(NODE);
        var columns=db.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('sharing_runtime_event','sharing_runtime_report')",String.class);
        assertThat(columns).doesNotContain("token","password","business_rows","response_body","request_body");
    }
    @Test void sameReportReplayAndCrossReportEventReplayPreserveOriginalReceiptAndCountOnce(){
        var e=event();var r=report(List.of(e));var first=success(post(r,CONTROL));var retry=success(post(r,CONTROL));
        assertThat(first.path("accepted_events").asInt()).isEqualTo(1);assertThat(retry.path("replayed").asBoolean()).isTrue();assertThat(retry.path("received_at")).isEqualTo(first.path("received_at"));
        var second=success(post(report(List.of(e)),CONTROL));assertThat(second.path("accepted_events").asInt()).isZero();assertThat(second.path("duplicate_events").asInt()).isEqualTo(1);assertThat(countEvents()).isEqualTo(1);
        var w=windows("REST_PROVIDER","&resourceId="+resource).get(0);assertThat(w.path("calls").asLong()).isEqualTo(1);assertThat(w.path("records").asLong()).isEqualTo(2);assertThat(w.path("bytes").asLong()).isEqualTo(512);assertThat(w.path("messages").isNull()).isTrue();
    }
    @Test void reorderedRetryIsEquivalentButChangedReportAndChangedEventRollbackWholeBatch(){
        var a=event();var b=event();a.put("event_id","ffffffff-ffff-ffff-ffff-"+uuid().substring(24));var r=report(List.of(a,b));success(post(r,CONTROL));
        r.put("events",List.of(b,a));assertThat(success(post(r,CONTROL)).path("replayed").asBoolean()).isTrue();
        r.put("window_closed",true);rejected(r);
        var conflict=new LinkedHashMap<>(a);conflict.put("byte_count",513);var fresh=event();fresh.put("event_id","00000000-0000-0000-0000-"+uuid().substring(24));var batch=report(List.of(fresh,conflict));rejected(batch);
        assertThat(countEvents()).isEqualTo(2);assertThat(db.queryForObject("SELECT COUNT(*) FROM sharing_runtime_report WHERE report_id=?",Long.class,batch.get("report_id"))).isZero();
        var traceCollision=event();traceCollision.put("trace_id",b.get("trace_id"));rejected(report(List.of(traceCollision)));assertThat(countEvents()).isEqualTo(2);
    }
    @Test void concurrentReportsAndSameReportRetriesDoNotDoubleCount()throws Exception{
        var e=event();var batches=new ArrayList<Map<String,Object>>();for(int i=0;i<8;i++)batches.add(report(List.of(e)));
        var executor=Executors.newFixedThreadPool(4);
        try{
            var futures=new ArrayList<Future<RuntimeTelemetryService.Receipt>>();
            for(var batch:batches){var dto=json.convertValue(batch,RuntimeReportRequest.class);futures.add(executor.submit(()->service.accept(dto)));}
            int accepted=0,duplicates=0;for(var future:futures){var receipt=future.get(15,TimeUnit.SECONDS);accepted+=receipt.acceptedEvents();duplicates+=receipt.duplicateEvents();}
            assertThat(accepted).isEqualTo(1);assertThat(duplicates).isEqualTo(7);assertThat(countEvents()).isEqualTo(1);
            var second=event();var dto=json.convertValue(report(List.of(second)),RuntimeReportRequest.class);futures.clear();
            for(int i=0;i<8;i++)futures.add(executor.submit(()->service.accept(dto)));
            long fresh=0;for(var future:futures)if(!future.get(15,TimeUnit.SECONDS).replayed())fresh++;
            assertThat(fresh).isEqualTo(1);assertThat(countEvents()).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }
    @Test void counterTypesWindowMembershipVersionsAndReasonWhitelistAreEnforced(){
        for(Object value:List.of(-1,1.5,"2",101)){var e=event();e.put("record_count",value);rejected(report(List.of(e)));}
        for(Object value:List.of(-1,134217729,1.5,"20")){var e=event();e.put("byte_count",value);rejected(report(List.of(e)));}
        var e=event();e.put("reason_code","raw exception containing PASSWORD");var response=post(report(List.of(e)),CONTROL);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody().toString()).doesNotContain("PASSWORD");
        e=event();e.remove("policy_version");rejected(report(List.of(e)));
        e=event();e.put("completed_at",start.plusSeconds(60).toString());rejected(report(List.of(e)));
        e=event();e.put("started_at",start.plusSeconds(10).toString());rejected(report(List.of(e)));
        e=event();e.put("completed_at",start.plusNanos(1030000001).toString());rejected(report(List.of(e)));
        var r=report(List.of(event()));r.put("window_start",start.plusSeconds(1).toString());rejected(r);
        r=report(List.of(event()));r.put("window_until",start.plusSeconds(120).toString());rejected(r);
        r=report(List.of());r.put("window_start","2200-01-01T00:00:00Z");r.put("window_until","2200-01-01T00:01:00Z");rejected(r);
        // Next minute can fall within the allowed five-second clock skew near a minute boundary.
        Instant future=Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(120);
        r=report(List.of());r.put("window_start",future.toString());r.put("window_until",future.plusSeconds(60).toString());rejected(r);
        e=event();rejected(report(List.of(e,e)));assertThat(countEvents()).isZero();
    }
    @Test void unknownZeroPartialClosedAndDelayedWindowsAreDistinguished(){
        var rows=windows("REST_PROVIDER","");assertThat(rows.size()).isEqualTo(3);assertThat(rows.get(0).path("state").asText()).isEqualTo("MISSING");assertThat(rows.get(0).path("calls").isNull()).isTrue();
        var empty=report(List.of());success(post(empty,CONTROL));rows=windows("REST_PROVIDER","");assertThat(rows.get(0).path("state").asText()).isEqualTo("PARTIAL");assertThat(rows.get(0).path("calls").isNull()).isTrue();
        var closed=report(List.of());closed.put("window_closed",true);success(post(closed,CONTROL));rows=windows("REST_PROVIDER","");assertThat(rows.get(0).path("state").asText()).isEqualTo("CLOSED");assertThat(rows.get(0).path("calls").asLong()).isZero();assertThat(rows.get(0).path("late_reports").asLong()).isEqualTo(2);assertThat(rows.get(0).path("maximum_report_delay_ms").asLong()).isPositive();
        var scoped=windows("REST_PROVIDER","&resourceId="+resource).get(0);assertThat(scoped.path("state").asText()).isEqualTo("UNKNOWN");assertThat(scoped.path("calls").isNull()).isTrue();
        success(post(report(List.of(event())),CONTROL));scoped=windows("REST_PROVIDER","&resourceId="+resource).get(0);assertThat(scoped.path("state").asText()).isEqualTo("CLOSED");assertThat(scoped.path("calls").asLong()).isEqualTo(1);
        assertThat(windows("KAFKA_PRODUCER","").get(0).path("messages").isNull()).isTrue();
        assertThat(http.getForEntity("/api/v1/metrics/windows?collectionPoint=ALL",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/metrics/windows?collectionPoint=REST_PROVIDER&from="+start.plusSeconds(1)+"&until="+start.plusSeconds(60),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/metrics/windows?collectionPoint=REST_PROVIDER&from="+start.minus(2,ChronoUnit.DAYS)+"&until="+start,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void restCallsAndKafkaMessagesUseDifferentUnitsAndOnlySuccessfulTrafficCounts(){
        success(post(report(List.of(event(),denied())),CONTROL));var k=event();k.put("http_status",202);k.put("record_count",1);k.put("byte_count",256);k.put("topic","port.container.event.v1");k.put("topic_partition",0);k.put("topic_offset",123);
        var r=report(List.of(k));r.put("collection_point","KAFKA_PRODUCER");success(post(r,CONTROL));
        var rest=windows("REST_PROVIDER","&resourceId="+resource).get(0);var kafka=windows("KAFKA_PRODUCER","&resourceId="+resource).get(0);
        assertThat(rest.path("calls").asLong()).isEqualTo(2);assertThat(rest.path("denied").asLong()).isEqualTo(1);assertThat(rest.path("records").asLong()).isEqualTo(2);assertThat(rest.path("unit").asText()).isEqualTo("CALL");
        assertThat(kafka.path("messages").asLong()).isEqualTo(1);assertThat(kafka.path("calls").isNull()).isTrue();assertThat(kafka.path("bytes").asLong()).isEqualTo(256);assertThat(kafka.path("unit").asText()).isEqualTo("MESSAGE");
        var existingProvider=new LinkedHashMap<>(k);existingProvider.put("event_id",uuid());existingProvider.put("trace_id",uuid());existingProvider.put("http_status",200);
        var actualStatus=report(List.of(existingProvider));actualStatus.put("collection_point","KAFKA_PRODUCER");success(post(actualStatus,CONTROL));assertThat(windows("KAFKA_PRODUCER","&resourceId="+resource).get(0).path("messages").asLong()).isEqualTo(2);
        var bad=denied();bad.put("record_count",1);rejected(report(List.of(bad)));bad=event();bad.put("topic","port.container.event.v1");rejected(report(List.of(bad)));
        k.put("event_id",uuid());k.put("trace_id",uuid());k.put("topic","arbitrary-topic");r=report(List.of(k));r.put("collection_point","KAFKA_PRODUCER");rejected(r);
        k.remove("topic");r=report(List.of(k));r.put("collection_point","KAFKA_PRODUCER");rejected(r);
    }
    @Test void interruptedCommittedResponseIsAnErrorEvenWhenHttpStatusWasAlready200(){
        var e=event();e.put("outcome","ERROR");e.put("reason_code","SERIALIZATION_ERROR");e.put("record_count",0);e.put("byte_count",0);success(post(report(List.of(e)),CONTROL));
        var w=windows("REST_PROVIDER","&resourceId="+resource).get(0);assertThat(w.path("calls").asLong()).isEqualTo(1);assertThat(w.path("errors").asLong()).isEqualTo(1);assertThat(w.path("successes").asLong()).isZero();assertThat(w.path("bytes").asLong()).isZero();
        e.put("event_id",uuid());e.put("trace_id",uuid());e.put("reason_code","SOURCE_UNAVAILABLE");rejected(report(List.of(e)));
    }
    @Test void unknownIdentityIsExplicitAndAuditTraceQueriesPreserveHistoricalVersions(){
        var allowed=event();var denied=denied();for(String key:List.of("subject_id","resource_id","policy_id","policy_version","relationship_version"))denied.remove(key);denied.put("reason_code","IDENTITY_INVALID");denied.put("http_status",401);
        success(post(report(List.of(allowed,denied)),CONTROL));String eventId=allowed.get("event_id").toString(),traceId=allowed.get("trace_id").toString();
        var row=success(http.getForEntity("/api/v1/runtime-events/"+eventId,JsonNode.class));assertThat(row.path("relationship_version").asInt()).isEqualTo(4);assertThat(row.path("policy_version").asInt()).isEqualTo(1);assertThat(row.path("collector_id").asText()).isEqualTo(NODE);
        assertThat(success(http.getForEntity("/api/v1/runtime-traces/"+traceId,JsonNode.class)).get(0).path("event_id").asText()).isEqualTo(eventId);
        var filtered=success(http.getForEntity("/api/v1/runtime-events?subjectId="+subject+"&resourceId="+resource+"&relationshipId="+relationship+"&capabilityId="+capability+"&outcome=SUCCESS&from="+start+"&until="+start.plusSeconds(60)+"&limit=1",JsonNode.class));assertThat(filtered.path("total").asLong()).isEqualTo(1);assertThat(filtered.path("rows").size()).isEqualTo(1);
        assertThat(success(http.getForEntity("/api/v1/runtime-events?traceId="+traceId+"&offset=1",JsonNode.class)).path("rows").isEmpty()).isTrue();
        assertThat(success(http.getForEntity("/api/v1/runtime-events/"+denied.get("event_id"),JsonNode.class)).path("subject_id").isNull()).isTrue();
        assertThat(http.getForEntity("/api/v1/runtime-events?limit=201",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/runtime-events?subjectId=not-an-id",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/runtime-events/"+uuid(),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='container_event'",Long.class)).isZero();
    }
}
