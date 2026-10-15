package cn.railway.provider;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeTelemetryReporterTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final List<RuntimeTelemetryReporter> reporters=new ArrayList<>();
    final List<HttpServer> servers=new ArrayList<>();
    static final String CONTROL="telemetry_"+"t".repeat(43);
    HttpServer server(com.sun.net.httpserver.HttpHandler handler)throws IOException{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/v1/telemetry/reports",handler);server.start();servers.add(server);return server;
    }
    MockEnvironment config(HttpServer server,Path path){return new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/port_source_test")
            .withProperty("PROVIDER_FOUNDATION_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()).withProperty("PROVIDER_CONTROL_TOKEN",CONTROL).withProperty("PROVIDER_TELEMETRY_SPOOL_DIR",path.toString());}
    RuntimeTelemetryReporter reporter(MockEnvironment env){var r=new RuntimeTelemetryReporter(env,json,new CapabilityRuntimeGuard(env,json));reporters.add(r);return r;}
    RuntimeObservation event(){var now=Instant.now().truncatedTo(ChronoUnit.MILLIS);return new RuntimeObservation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"REL_"+"1".repeat(32),"CAP_"+"2".repeat(32),"SUB_"+"3".repeat(32),"RES_"+"4".repeat(32),"POL_"+"5".repeat(32),4,1,now.minusMillis(30),now,"SUCCESS","COMPLETED",200,1,123,30,null,null,null);}
    void await(BooleanSupplier done)throws InterruptedException{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(20);assertThat(done.getAsBoolean()).isTrue();}
    void ack(com.sun.net.httpserver.HttpExchange exchange,JsonNode report,boolean replayed)throws IOException{
        byte[] bytes=json.writeValueAsBytes(Map.of("code",0,"data",Map.of("report_id",report.path("report_id").asText(),"accepted_events",report.path("events").size(),"duplicate_events",0,"replayed",replayed,"received_at",Instant.now().toString())));
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @AfterEach void close(){reporters.forEach(RuntimeTelemetryReporter::close);servers.forEach(s->s.stop(0));}

    @Test void lostReceiptAndProcessRestartResendExactReportWithoutDoubleCountingOrSecrets()throws Exception{
        var mode=new AtomicBoolean(false);var reports=new ConcurrentHashMap<String,JsonNode>();var attempts=new AtomicInteger();var counted=new AtomicInteger();
        var server=server(exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("X-Provider-Token")).isEqualTo(CONTROL);
            var body=json.readTree(exchange.getRequestBody());assertThat(body.toString()).doesNotContain(CONTROL,"password","Authorization","container_no");
            String id=body.path("report_id").asText();var first=reports.putIfAbsent(id,body);if(first==null)counted.addAndGet(body.path("events").size());else assertThat(body).isEqualTo(first);
            attempts.incrementAndGet();if(mode.get())ack(exchange,body,first!=null);else{byte[] bytes="unconfirmed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
        });
        var env=config(server,temp.resolve("retry"));var first=reporter(env);first.submit("REST_PROVIDER",event());await(()->first.status().deliveryFailures()>0);first.close();
        assertThat(first.status().pendingReports()).isEqualTo(1);assertThat(counted.get()).isEqualTo(1);mode.set(true);
        var recovered=reporter(env);await(()->recovered.status().deliveredReports()==1);assertThat(recovered.status().pendingReports()).isZero();assertThat(counted.get()).isEqualTo(1);assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        try(var paths=Files.list(temp.resolve("retry"))){assertThat(paths.filter(p->p.toString().endsWith(".json")).count()).isZero();}
    }
    @Test void redirectNeverReceivesTheServiceCredentialAndPendingReportIsPreserved()throws Exception{
        var leaked=new AtomicInteger();var server=server(exchange->{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().add("Location","/leak");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/leak",exchange->{leaked.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        var r=reporter(config(server,temp.resolve("redirect")));r.submit("REST_PROVIDER",event());await(()->r.status().deliveryFailures()>0);
        assertThat(r.status().pendingReports()).isEqualTo(1);assertThat(leaked.get()).isZero();assertThat(r.status().lastFailureCode()).isEqualTo("DELIVERY_UNCONFIRMED");
    }
    @Test void queuePressureDoesNotWaitForBlockedDeliveryAndDropsAreVisible()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var server=server(exchange->{exchange.getRequestBody().readAllBytes();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.sendResponseHeaders(503,-1);exchange.close();});
        var r=reporter(config(server,temp.resolve("pressure")).withProperty("PROVIDER_TELEMETRY_QUEUE_CAPACITY","1").withProperty("PROVIDER_TELEMETRY_MAX_PENDING_REPORTS","1"));
        r.submit("REST_PROVIDER",event());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        long before=System.nanoTime();assertThat(r.submit("REST_PROVIDER",event())).isTrue();assertThat(r.submit("REST_PROVIDER",event())).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)).isLessThan(200);assertThat(r.status().droppedEvents()).isEqualTo(1);assertThat(r.status().queuedEvents()).isEqualTo(1);release.countDown();
    }
    @Test void diskFailureRemainsVisibleAndQueuedSummaryIsDeliveredAfterRepair()throws Exception{
        var server=server(exchange->{var body=json.readTree(exchange.getRequestBody());ack(exchange,body,false);});var blocked=temp.resolve("blocked");Files.writeString(blocked,"occupied");
        var r=reporter(config(server,blocked));assertThat(r.submit("REST_PROVIDER",event())).isTrue();await(()->r.status().spoolFailures()>0);
        assertThat(r.status().storageReady()).isFalse();assertThat(r.status().queuedEvents()).isEqualTo(1);Files.delete(blocked);await(()->r.status().deliveredReports()==1);assertThat(r.status().queuedEvents()).isZero();
    }
    @Test void rejectedAndCorruptReportsAreRetainedAndRemainVisibleAfterRestart()throws Exception{
        var server=server(exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(400,-1);exchange.close();});var spool=temp.resolve("quarantine");
        var r=reporter(config(server,spool));r.submit("REST_PROVIDER",event());await(()->r.status().rejectedReports()==1);r.close();
        Files.writeString(spool.resolve(UUID.randomUUID()+".json"),"{\"business_rows\":\"DO_NOT_FORWARD\"}");var second=reporter(config(server,spool));await(()->second.status().storageReady());
        assertThat(second.status().quarantinedReports()).isEqualTo(2);assertThat(second.status().spoolFailures()).isGreaterThanOrEqualTo(1);assertThat(second.status().pendingReports()).isZero();
    }
    @Test void outputFailureAfterHttpHeadersAreCommittedNeverClaimsSuccessfulSharedRecords()throws Exception{
        var reporter=mock(RuntimeTelemetryReporter.class);var filter=new RuntimeTelemetryFilter(reporter);var request=new MockHttpServletRequest("GET","/containers/events");var response=new MockHttpServletResponse();
        assertThatThrownBy(()->filter.doFilter(request,response,(req,res)->{
            var c=RuntimeCapture.from((jakarta.servlet.http.HttpServletRequest)req);c.restSuccess(1);
            res.getOutputStream().write("partial".getBytes(StandardCharsets.UTF_8));res.flushBuffer();throw new IOException("PRIVATE_BUSINESS_VALUE");
        })).isInstanceOf(IOException.class);
        var captured=org.mockito.ArgumentCaptor.forClass(RuntimeObservation.class);verify(reporter).submit(eq("REST_PROVIDER"),captured.capture());var summary=captured.getValue();
        assertThat(summary.httpStatus()).isEqualTo(200);assertThat(summary.outcome()).isEqualTo("ERROR");assertThat(summary.reasonCode()).isEqualTo("SERIALIZATION_ERROR");assertThat(summary.recordCount()).isZero();assertThat(summary.byteCount()).isZero();
        assertThat(json.writeValueAsString(summary)).doesNotContain("partial","PRIVATE_BUSINESS_VALUE");
    }
    @Test void disabledCollectorCreatesNoReportsAndOperationalStatusContainsNoSecretsOrPaths()throws Exception{
        var calls=new AtomicInteger();var server=server(exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});
        var r=reporter(config(server,temp.resolve("disabled")).withProperty("PROVIDER_TELEMETRY_ENABLED","false"));assertThat(r.submit("REST_PROVIDER",event())).isFalse();assertThat(r.status().enabled()).isFalse();assertThat(calls.get()).isZero();
        assertThat(json.writeValueAsString(r.status())).doesNotContain(CONTROL,temp.toString(),"jdbc:","password","report_id","subject_id");assertThat(Files.exists(temp.resolve("disabled"))).isFalse();
    }
}
