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
@EmbeddedKafka(partitions=1,topics="port.container.event.v1",bootstrapServersProperty="PROVIDER_KAFKA_BOOTSTRAP_SERVERS")
class ProviderIntegrationTest {
    @Autowired TestRestTemplate http;
    @MockitoSpyBean ContainerEventService sourceService;
    static final String CAPABILITY="CAP_0123456789abcdef0123456789abcdef";
    static final AtomicReference<String> runtimeMode=new AtomicReference<>("ENABLED");
    static final HttpServer foundation=fakeFoundation();
    static HttpServer fakeFoundation() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/capabilities/",exchange->{
                if (!exchange.getRequestURI().getPath().equals("/api/v1/capabilities/"+CAPABILITY+"/runtime")) {
                    exchange.sendResponseHeaders(404,-1); exchange.close(); return;
                }
                String mode=runtimeMode.get();
                if (mode.equals("UNAVAILABLE")) { exchange.sendResponseHeaders(503,-1); exchange.close(); return; }
                var source=URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));
                String payload=mode.equals("MALFORMED")?"broken-json":String.format(
                        "{\"code\":0,\"data\":{\"capability_id\":\"%s\",\"status\":\"%s\",\"enabled\":%s,\"source_host\":\"%s\",\"source_port\":%d,\"database_name\":\"%s\",\"physical_table\":\"container_event\"}}",
                        CAPABILITY,mode.equals("DISABLED")?"DISABLED":"ENABLED",!mode.equals("DISABLED"),source.getHost(),source.getPort(),
                        mode.equals("WRONG_SOURCE")?"wrong_source_db":source.getPath().substring(1));
                byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch(IOException ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void closeFoundation() { foundation.stop(0); }
    @BeforeEach void resetRuntime() { runtimeMode.set("ENABLED"); clearInvocations(sourceService); }

    private HttpEntity<Void> request(String id) {
        var headers=new HttpHeaders(); headers.set("X-Capability-Id",id); return new HttpEntity<>(headers);
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
            var published=http.postForEntity("/containers/events/"+id+"/publish",null,JsonNode.class);
            assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(published.getBody().path("data").path("topic").asText()).isEqualTo("port.container.event.v1");
            boolean found=false;
            for (int attempt=0;attempt<10 && !found;attempt++) {
                for (var message:consumer.poll(Duration.ofMillis(500))) {
                    var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.value());
                    if (payload.path("id").asLong()==id) {
                        assertThat(message.key()).isEqualTo(event.path("container_no").asText());
                        assertThat(payload.path("event_type").asText()).isEqualTo(event.path("event_type").asText());
                        found=true;
                    }
                }
            }
            assertThat(found).isTrue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new AssertionError(ex); }
        assertThat(http.postForEntity("/containers/events/0/publish",null,JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
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
        verify(sourceService,never()).search(any(),any(),any(),any(),any(),anyInt(),anyInt());
        runtimeMode.set("ENABLED");
        assertThat(query("/containers/events?limit=1").path("data").size()).isEqualTo(1);
        runtimeMode.set("DISABLED");
        assertThat(http.exchange("/containers/events?limit=1",HttpMethod.GET,request(CAPABILITY),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sourceService,times(1)).search(any(),any(),any(),any(),any(),anyInt(),anyInt());
        runtimeMode.set("UNAVAILABLE");
        assertThat(http.exchange("/containers/events",HttpMethod.HEAD,null,Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
