package cn.railway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.nio.file.Path;
import java.sql.DriverManager;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions=1,topics="port.container.event.v1",bootstrapServersProperty="PROVIDER_KAFKA_BOOTSTRAP_SERVERS",
    brokerProperties={"offsets.topic.num.partitions=1","offsets.topic.replication.factor=1","log.index.size.max.bytes=65536","log.segment.bytes=1048576","offsets.topic.segment.bytes=1048576","group.initial.rebalance.delay.ms=0"})
class ProviderIntegrationTest {
    @Autowired TestRestTemplate http;
    @MockitoSpyBean ContainerEventService sourceService;
    @Autowired RuntimeTelemetryReporter telemetry;
    static final String CAPABILITY="CAP_0123456789abcdef0123456789abcdef";
    static final String RELATIONSHIP="REL_0123456789abcdef0123456789abcdef";
    static final String CALLER="rdf_"+"a".repeat(43),CONTROL="test_"+"c".repeat(43);
    static final AtomicReference<String> runtimeMode=new AtomicReference<>("ENABLED");
    static final Path telemetrySpool=Path.of(".runtime","provider-telemetry-test-"+UUID.randomUUID()).toAbsolutePath();
    static final HttpServer foundation=fakeFoundation();
    static HttpServer fakeFoundation() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/telemetry/reports",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(503,-1);exchange.close();});
            server.createContext("/api/v1/capabilities/",exchange->{
                if(exchange.getRequestURI().getPath().equals("/api/v1/capabilities/"+CAPABILITY+"/producer-context")){
                    var source=URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));var mode=runtimeMode.get();
                    int status=exchange.getRequestHeaders().getFirst("Cookie")==null?401:mode.equals("DISABLED")?403:200;
                    if(exchange.getRequestMethod().equals("POST")&&exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN")==null)status=403;
                    var state=new java.util.HashMap<String,Object>();state.put("capability_id",CAPABILITY);state.put("type","KAFKA_TOPIC");state.put("status","ENABLED");state.put("enabled",true);state.put("resource_id","RES_"+"2".repeat(32));state.put("source_host",source.getHost());state.put("source_port",source.getPort());state.put("database_name",source.getPath().substring(1));state.put("physical_table","container_event");state.put("topic","port.container.event.v1");state.put("auth_type","NONE");
                    byte[] bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(java.util.Map.of("code",0,"data",java.util.Map.of("user_id","USR_"+"4".repeat(32),"username","test_provider","subject_id","SUB_"+"5".repeat(32),"runtime",state)));
                    exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                }
                if (!List.of("/api/v1/capabilities/"+CAPABILITY+"/runtime","/api/v1/capabilities/"+CAPABILITY+"/kafka-runtime").contains(exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404,-1); exchange.close(); return;
                }
                String mode=runtimeMode.get();
                if (mode.equals("UNAVAILABLE")) { exchange.sendResponseHeaders(503,-1); exchange.close(); return; }
                var source=URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));
                String payload=mode.equals("MALFORMED")?"broken-json":String.format(
                        "{\"code\":0,\"data\":{\"capability_id\":\"%s\",\"status\":\"%s\",\"enabled\":%s,\"source_host\":\"%s\",\"source_port\":%d,\"database_name\":\"%s\",\"physical_table\":\"container_event\"}}",
                        CAPABILITY,mode.equals("DISABLED")?"DISABLED":"ENABLED",!mode.equals("DISABLED"),source.getHost(),source.getPort(),
                        mode.equals("WRONG_SOURCE")?"wrong_source_db":source.getPath().substring(1));
                if(exchange.getRequestURI().getPath().endsWith("/kafka-runtime") && !mode.equals("MALFORMED")) {
                    var node=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                    var state=(com.fasterxml.jackson.databind.node.ObjectNode)node.path("data");
                    state.put("type",mode.equals("WRONG_TYPE")?"REST_API":"KAFKA_TOPIC");
                    state.put("resource_id",mode.equals("BAD_RESOURCE")?"RES_bad":"RES_"+"2".repeat(32));
                    state.put("topic",mode.equals("WRONG_TOPIC")?"foreign.topic":"port.container.event.v1");
                    state.put("auth_type",mode.equals("WRONG_AUTH")?"SASL":"NONE");payload=node.toString();
                }
                byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.createContext("/api/v1/access/check",exchange->{
                if(!CONTROL.equals(exchange.getRequestHeaders().getFirst("X-Provider-Token"))||!("Bearer "+CALLER).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);exchange.close();return;}
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                var request=mapper.readTree(exchange.getRequestBody());String mode=runtimeMode.get();
                if(mode.equals("UNAVAILABLE")){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
                var source=URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));
                var decision=new java.util.LinkedHashMap<String,Object>();
                decision.put("allowed",!mode.equals("DISABLED")&&CAPABILITY.equals(request.path("capability_id").asText()));
                decision.put("reason_code",Boolean.TRUE.equals(decision.get("allowed"))?"ALLOWED":"CAPABILITY_UNAVAILABLE");decision.put("reason","测试授权决策");
                decision.put("relationship_id",request.path("relationship_id").asText());decision.put("capability_id",request.path("capability_id").asText());
                decision.put("subject_id","SUB_"+"1".repeat(32));decision.put("resource_id","RES_"+"2".repeat(32));decision.put("policy_id","POL_"+"3".repeat(32));
                decision.put("relationship_version",4);decision.put("policy_version",1);
                decision.put("allowed_fields",request.path("requested_fields").isArray()?request.path("requested_fields"):List.of("id","container_no","event_type","event_time","port_code","yard_code","train_no","operation_status","equipment_no","source_system","created_at","updated_at"));
                decision.put("filter_fields",request.path("filter_fields"));decision.put("time_field","event_time");
                decision.put("data_from",request.path("from").isTextual()?request.path("from").asText():"2026-09-19T00:00:00Z");decision.put("data_until",request.path("to").isTextual()?request.path("to").asText():"2026-09-21T00:00:00Z");
                decision.put("source_host",source.getHost());decision.put("source_port",source.getPort());decision.put("database_name",mode.equals("WRONG_SOURCE")?"wrong_source_db":source.getPath().substring(1));decision.put("physical_table","container_event");
                decision.put("checked_at",java.time.Instant.now().toString());decision.put("valid_until",java.time.Instant.now().plusSeconds(60).toString());
                byte[] bytes=(mode.equals("MALFORMED")?"broken-json":mapper.writeValueAsString(java.util.Map.of("code",0,"data",decision))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start(); return server;
        } catch(IOException ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void closeFoundation() { foundation.stop(0); }
    @BeforeEach void resetRuntime() { runtimeMode.set("ENABLED"); clearInvocations(sourceService); }

    private RuntimeObservation persistedSummary(String point,String trace)throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            if(java.nio.file.Files.isDirectory(telemetrySpool))try(var files=java.nio.file.Files.newDirectoryStream(telemetrySpool,"*.json")) {
                for(var file:files) {
                    var report=mapper.readValue(java.nio.file.Files.readAllBytes(file),RuntimeObservation.Report.class);
                    if(report.collectionPoint().equals(point))for(var event:report.events())if(event.traceId().equals(trace))return event;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("未找到对应实际调用的持久化摘要");
    }

    private HttpEntity<Void> request(String id) {
        var headers=new HttpHeaders(); headers.set("X-Capability-Id",id);headers.set("X-Relationship-Id",RELATIONSHIP);headers.setBearerAuth(CALLER);return new HttpEntity<>(headers);
    }
    private JsonNode query(String path) {
        var response=http.exchange(path,HttpMethod.GET,request(CAPABILITY),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody();
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",()->System.getenv("TEST_SOURCE_DB_URL"));
        props.add("spring.datasource.username",()->System.getenv("TEST_PROVIDER_SOURCE_DB_USER"));
        props.add("spring.datasource.password",()->System.getenv("TEST_PROVIDER_SOURCE_DB_PASSWORD"));
        props.add("PROVIDER_KAFKA_TOPIC",()->"port.container.event.v1");
        props.add("PROVIDER_FOUNDATION_BASE_URL",()->"http://127.0.0.1:"+foundation.getAddress().getPort());
        props.add("PROVIDER_CONTROL_TOKEN",()->CONTROL);
        props.add("PROVIDER_TELEMETRY_ENABLED",()->"true");
        props.add("PROVIDER_TELEMETRY_SPOOL_DIR",()->telemetrySpool.toString());
    }
    @BeforeAll static void migrateSource() {
        Flyway.configure().dataSource(System.getenv("TEST_SOURCE_DB_URL"),System.getenv("TEST_SOURCE_DB_USER"),
                        System.getenv("TEST_SOURCE_DB_PASSWORD"))
                .locations("filesystem:"+Path.of("..","sql","source").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
    }
    @Test void providerReadsSourceDirectlyAndProbeReturnsNoBusinessRows() throws Exception {
        var head=http.exchange("/containers/events",HttpMethod.HEAD,null,Void.class);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(head.getHeaders().getFirst("X-Railway-Provider")).isEqualTo("container-event-v1");
        assertThat(head.getHeaders().getFirst("X-Railway-Runtime")).isEqualTo("rest-capability-v1");
        assertThat(head.getHeaders().getFirst("X-Railway-Access")).isEqualTo("sharing-access-v1");
        assertThat(head.getBody()).isNull();
        var all=query("/containers/events?limit=100");
        assertThat(all.path("code").asInt()).isZero();
        assertThat(all.path("data").size()).isEqualTo(20);
        var filtered=query("/containers/events?trainNo=X8021&eventType=LOAD&limit=2").path("data");
        assertThat(filtered.size()).isEqualTo(2);
        filtered.forEach(row->{
            assertThat(row.path("train_no").asText()).isEqualTo("X8021");
            assertThat(row.path("event_type").asText()).isEqualTo("LOAD");
        });
        try (var c=DriverManager.getConnection(System.getenv("TEST_SOURCE_DB_URL"),System.getenv("TEST_PROVIDER_SOURCE_DB_USER"),
                System.getenv("TEST_PROVIDER_SOURCE_DB_PASSWORD")); var st=c.createStatement(); var grants=st.executeQuery("SHOW GRANTS")) {
            StringBuilder permissions=new StringBuilder();
            while (grants.next()) permissions.append(grants.getString(1));
            assertThat(permissions.toString()).contains("SELECT").doesNotContain("ALL PRIVILEGES","INSERT","UPDATE","DELETE");
        }
    }
    @Test void rejectsInvalidFiltersAndPagination() {
        for (String query : new String[]{"limit=0","limit=101","offset=-1","eventType=UNKNOWN",
                "containerNo=x%27%20OR%201%3D1","from=2026-09-21T00:00:00&to=2026-09-20T00:00:00"}) {
            var response=http.exchange("/containers/events?"+query,HttpMethod.GET,request(CAPABILITY),JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("code").asInt()).isEqualTo(10001);
        }
    }
    @Test void workspaceRecordPaginationMatchesFiltersAndNoSourceReadBeforeAdmission(){
        var headers=new HttpHeaders();headers.set("Cookie","JSESSIONID=workspace123456789");headers.set("X-CSRF-TOKEN","test-csrf");
        String base="/api/v1/production/capabilities/"+CAPABILITY+"/records";
        var first=http.exchange(base+"?limit=10",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(first.getBody().path("data").path("total").asInt()).isEqualTo(20);assertThat(first.getBody().path("data").path("items").size()).isEqualTo(10);
        var filter=http.exchange(base+"?trainNo=X8021&eventType=LOAD&limit=1",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody().path("data");assertThat(filter.path("total").asInt()).isGreaterThan(1);assertThat(filter.path("items").size()).isEqualTo(1);
        var empty=http.exchange(base+"?offset=20",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody().path("data");assertThat(empty.path("items")).isEmpty();assertThat(empty.path("total").asInt()).isEqualTo(20);
        clearInvocations(sourceService);runtimeMode.set("DISABLED");assertThat(http.exchange(base,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);verify(sourceService,never()).page(any(),any(),any(),any(),any(),anyInt(),anyInt());
        runtimeMode.set("ENABLED");headers.remove("X-CSRF-TOKEN");assertThat(http.exchange(base+"/1/send",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.set("X-CSRF-TOKEN","test-csrf");assertThat(http.exchange(base+"/999999/send",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(base,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange(base+"?limit=51",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void publishesSourceEventToKafkaWithoutFoundation() {
        var event=query("/containers/events?limit=1").path("data").get(0);
        long id=event.path("id").asLong();
        var config=new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,System.getProperty("PROVIDER_KAFKA_BOOTSTRAP_SERVERS"));
        config.put(ConsumerConfig.GROUP_ID_CONFIG,"provider-test-"+UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        try (var consumer=new KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of("port.container.event.v1"));
            long assignedUntil=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(consumer.assignment().isEmpty()&&System.nanoTime()<assignedUntil)consumer.poll(Duration.ofMillis(200));
            assertThat(consumer.assignment()).as("Real test consumer must join before publication").isNotEmpty();
            var operatorHeaders=new HttpHeaders();operatorHeaders.set("X-Provider-Token",CONTROL);operatorHeaders.set("X-Capability-Id",CAPABILITY);
            var published=http.exchange("/containers/events/"+id+"/publish",HttpMethod.POST,new HttpEntity<>(operatorHeaders),JsonNode.class);
            assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(published.getBody().path("data").path("topic").asText()).isEqualTo("port.container.event.v1");
            boolean found=false;
            long receivedUntil=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while (!found && System.nanoTime()<receivedUntil) {
                for (var message:consumer.poll(Duration.ofMillis(500))) {
                    var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.value());
                    if (payload.path("id").asLong()==id && message.partition()==published.getBody().path("data").path("partition").asInt() && message.offset()==published.getBody().path("data").path("offset").asLong()) {
                        assertThat(message.key()).isEqualTo(event.path("container_no").asText());
                        assertThat(payload.path("event_type").asText()).isEqualTo(event.path("event_type").asText());
                        var summary=persistedSummary("KAFKA_PRODUCER",published.getHeaders().getFirst("X-Trace-Id"));assertThat(summary.outcome()).isEqualTo("SUCCESS");assertThat(summary.httpStatus()).isEqualTo(200);assertThat(summary.recordCount()).isEqualTo(1);
                        assertThat(summary.byteCount()).isEqualTo(message.value().getBytes(StandardCharsets.UTF_8).length);assertThat(summary.topicPartition()).isEqualTo(message.partition());assertThat(summary.topicOffset()).isEqualTo(message.offset());
                        assertThat(summary.subjectId()).isNull();assertThat(summary.capabilityId()).isEqualTo(CAPABILITY);
                        assertThat(summary.resourceId()).isEqualTo("RES_"+"2".repeat(32));assertThat(summary.relationshipId()).isNull();assertThat(summary.policyId()).isNull();
                        assertThat(published.getHeaders().getFirst("X-Trace-Id")).isEqualTo(summary.traceId());
                        found=true;
                    }
                }
            }
            assertThat(found).isTrue();
        } catch (Exception ex) { throw new AssertionError(ex); }
        assertThat(http.postForEntity("/containers/events/0/publish",null,JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void kafkaRuntimeDenialsStopBeforeSourceReads() {
        var headers=new HttpHeaders();headers.set("X-Provider-Token",CONTROL);headers.set("X-Capability-Id",CAPABILITY);
        for(String mode:List.of("DISABLED","WRONG_SOURCE","WRONG_TYPE","WRONG_TOPIC","WRONG_AUTH","BAD_RESOURCE","UNAVAILABLE","MALFORMED")) {
            runtimeMode.set(mode);
            var response=http.exchange("/containers/events/1/publish",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(List.of("UNAVAILABLE","MALFORMED","BAD_RESOURCE").contains(mode)?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.FORBIDDEN);
            assertThat(response.getBody().path("data").isNull()).isTrue();
        }
        runtimeMode.set("ENABLED");headers.remove("X-Capability-Id");
        assertThat(http.exchange("/containers/events/1/publish",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(sourceService,never()).findById(anyLong());
    }
    @Test void runtimeDenialsDoNotReadSourceRowsAndAStopAffectsTheNextRequest() {
        assertThat(http.getForEntity("/containers/events?limit=1",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/containers/events?limit=1",HttpMethod.GET,
                request("CAP_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (String mode:List.of("DISABLED","WRONG_SOURCE","UNAVAILABLE","MALFORMED")) {
            runtimeMode.set(mode);
            var response=http.exchange("/containers/events?limit=1",HttpMethod.GET,request(CAPABILITY),JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(mode.equals("DISABLED") || mode.equals("WRONG_SOURCE")
                    ?HttpStatus.FORBIDDEN:HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().path("data").isNull()).isTrue();
        }
        verify(sourceService,never()).searchAuthorized(any(),any(),any(),any(),any(),anyInt(),anyInt());
        runtimeMode.set("ENABLED");
        assertThat(query("/containers/events?limit=1").path("data").size()).isEqualTo(1);
        runtimeMode.set("DISABLED");
        assertThat(http.exchange("/containers/events?limit=1",HttpMethod.GET,request(CAPABILITY),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sourceService,times(1)).searchAuthorized(any(),any(),any(),any(),any(),anyInt(),anyInt());
        runtimeMode.set("UNAVAILABLE");
        assertThat(http.exchange("/containers/events",HttpMethod.HEAD,null,Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test void fieldProjectionAndUtcBoundaryAreAppliedBySourceQuery(){
        var row=query("/containers/events?fields=container_no,event_time&from=2026-09-20T00:10:00Z&to=2026-09-20T00:20:00Z").path("data");
        assertThat(row.size()).isEqualTo(1);assertThat(row.get(0).size()).isEqualTo(2);
        assertThat(row.get(0).has("id")).isFalse();assertThat(row.get(0).path("container_no").asText()).isEqualTo("GZPU0000001");
        assertThat(java.time.OffsetDateTime.parse(row.get(0).path("event_time").asText()).toInstant()).isEqualTo(java.time.Instant.parse("2026-09-20T00:10:00Z"));
    }
    @Test void automaticRestSummaryCountsExactResponseBytesAndNeverIncludesBusinessValuesOrReportedIdentity()throws Exception{
        var headers=new HttpHeaders();headers.set("X-Capability-Id",CAPABILITY);headers.set("X-Relationship-Id",RELATIONSHIP);headers.setBearerAuth(CALLER);
        headers.set("X-Subject-Id","SUB_"+"f".repeat(32));String spoofTrace=UUID.randomUUID().toString();headers.set("X-Trace-Id",spoofTrace);
        var response=http.exchange("/containers/events?fields=container_no,event_time&containerNo=GZPU0000001&limit=1",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody()).path("data").size()).isEqualTo(1);
        var summary=persistedSummary("REST_PROVIDER",response.getHeaders().getFirst("X-Trace-Id"));
        assertThat(summary.traceId()).isEqualTo(response.getHeaders().getFirst("X-Trace-Id")).isNotEqualTo(spoofTrace);assertThat(summary.recordCount()).isEqualTo(1);
        assertThat(summary.byteCount()).isEqualTo(response.getBody().getBytes(StandardCharsets.UTF_8).length);assertThat(summary.subjectId()).isEqualTo("SUB_"+"1".repeat(32));assertThat(summary.policyVersion()).isEqualTo(1);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(summary)).doesNotContain(CALLER,CONTROL,"GZPU0000001","container_no","event_time","Bearer","X-Subject-Id");
        long submitted=telemetry.status().submittedEvents();http.exchange("/containers/events",HttpMethod.HEAD,null,Void.class);http.getForEntity("/api/v1/provider/health",JsonNode.class);assertThat(telemetry.status().submittedEvents()).isEqualTo(submitted);
    }
    @Test void failedSourceQueryProducesErrorSummaryWithNoSharedRowsOrBytes()throws Exception{
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("SECRET_SQL_OR_PASSWORD")).when(sourceService).searchAuthorized(any(),any(),any(),any(),any(),anyInt(),anyInt());
        var response=http.exchange("/containers/events",HttpMethod.GET,request(CAPABILITY),JsonNode.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);assertThat(response.getBody().toString()).doesNotContain("SECRET_SQL_OR_PASSWORD");
        var summary=persistedSummary("REST_PROVIDER",response.getHeaders().getFirst("X-Trace-Id"));
        assertThat(summary.outcome()).isEqualTo("ERROR");assertThat(summary.reasonCode()).isEqualTo("SOURCE_UNAVAILABLE");assertThat(summary.recordCount()).isZero();assertThat(summary.byteCount()).isZero();assertThat(summary.subjectId()).isNotNull();
    }
    @Test void telemetryOutageDoesNotBlockAuthorizedSourceQueryAndPendingReportsAreVisible()throws Exception{
        var rows=query("/containers/events?limit=1");assertThat(rows.path("data").size()).isEqualTo(1);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(telemetry.status().deliveryFailures()==0&&System.nanoTime()<until)Thread.sleep(20);
        assertThat(telemetry.status().deliveryFailures()).isPositive();assertThat(telemetry.status().pendingReports()).isPositive();
        assertThat(http.getForEntity("/api/v1/provider/telemetry",JsonNode.class).getStatusCode().value()).isEqualTo(403);var statusHeaders=new HttpHeaders();statusHeaders.set("X-Provider-Token",CONTROL);var status=http.exchange("/api/v1/provider/telemetry",HttpMethod.GET,new HttpEntity<>(statusHeaders),JsonNode.class);assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(status.getBody().toString()).doesNotContain(CONTROL,CALLER,"jdbc:","container_no");
        verify(sourceService,times(1)).searchAuthorized(any(),any(),any(),any(),any(),anyInt(),anyInt());
    }
    @Test void capabilityOnlyOrWrongCredentialCannotReadRowsOrTriggerKafka(){
        var headers=new HttpHeaders();headers.set("X-Capability-Id",CAPABILITY);headers.set("X-Relationship-Id",RELATIONSHIP);
        assertThat(http.exchange("/containers/events",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        headers.setBearerAuth("SUB_"+"1".repeat(32));
        assertThat(http.exchange("/containers/events",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/containers/events/1/publish",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sourceService,never()).searchAuthorized(any(),any(),any(),any(),any(),anyInt(),anyInt());verify(sourceService,never()).findById(anyLong());
    }
}
