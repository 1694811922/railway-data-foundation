package cn.railway.example.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kafka.testkit.KafkaClusterTestKit;
import kafka.testkit.TestKitNodes;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Opt-in full chain: real HTTP services, read-only simulated Source, SCRAM Broker and real browser. */
@EnabledIfEnvironmentVariable(named="RUN_KAFKA_FOUNDATION_LIVE",matches="true")
class KafkaFoundationLiveTest {
    final ObjectMapper json=new ObjectMapper();
    final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    JsonNode csrf;
    final String workspaceName="workspace_"+runId(),workspacePassword=random(),workspaceDemandName="demand_"+runId(),workspaceDemandPassword=random();
    final String workspaceProviderName="provider_"+runId(),workspaceProviderPassword=random();
    final String workspaceOtherDemandName="demand_b_"+runId(),workspaceOtherDemandPassword=random();
    static String runId(){return UUID.randomUUID().toString().replace("-","");}
    final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    final String run=UUID.randomUUID().toString().replace("-","");
    final String topic="foundation-live-"+run,group="live-group-"+run,control=random();
    final String probe="probe_"+run,writer="writer_"+run,reader="reader_"+run;
    final String probePassword=random(),writerPassword=random(),readerPassword=random(),adminPassword=random();
    Path project,evidence;String foundation,provider,sourceId,resourceId,capabilityId,credentialRef;
    Process foundationProcess,providerProcess,browserProcess,receiverProcess;
    String receiver,receiverTask;Map<String,String> receiverEnvironment;
    KafkaClusterTestKit broker;Admin admin;
    Properties nativeIdentity;
    String brokerAddress,operatorUser="fixture_admin",operatorPassword=adminPassword,operatorMechanism="PLAIN";
    final List<String> subscriptionIds=new ArrayList<>(),subjectIds=new ArrayList<>();
    String providerSubject,demandSubject;
    String restCapability,restRelationship,restPolicy,restToken,ownedRestRelationship,ownedRestPolicy;
    int simulatedMessages=1;long simulatedBytes;
    final List<Map<String,Object>> simulatedReceipts=new ArrayList<>();

    static String random(){return UUID.randomUUID().toString().replace("-","");}
    String env(String key){var value=System.getenv(key);assertThat(value).as("Required environment: "+key).isNotBlank();return value;}
    String jdbc(){var url=env("KAFKA_LIVE_DB_URL");assertThat(url).matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/foundation_kafka_live_test\\?.*$");return url;}
    Connection db()throws Exception{var c=DriverManager.getConnection(jdbc(),env("FOUNDATION_DB_USER"),env("FOUNDATION_DB_PASSWORD"));assertThat(c.getCatalog()).isEqualTo("foundation_kafka_live_test");return c;}
    int port()throws Exception{try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    Properties login(String user,String password,String mechanism){return KafkaCallerConfig.create(brokerAddress,"SASL_PLAINTEXT",mechanism,user,password);}
    void broker()throws Exception{
        String nativeFile=System.getenv("KAFKA_LIVE_NATIVE_IDENTITY");
        if(nativeFile!=null&&!nativeFile.isBlank()) {
            var allowed=project.resolve(".runtime/local/kafka/identity.properties").toRealPath();
            assertThat(Path.of(nativeFile).toRealPath()).isEqualTo(allowed);
            nativeIdentity=new Properties();try(var r=Files.newBufferedReader(allowed,StandardCharsets.UTF_8)){nativeIdentity.load(r);}
            brokerAddress="127.0.0.1:"+nativeIdentity.getProperty("port");operatorUser=nativeIdentity.getProperty("admin.username");operatorPassword=nativeIdentity.getProperty("admin.password");operatorMechanism="PLAIN";
        } else {
        // Controller ANONYMOUS is restricted to this loopback temporary test, never a deployment recipe.
        broker=new KafkaClusterTestKit.Builder(new TestKitNodes.Builder().setCombined(true).setNumBrokerNodes(1).setNumControllerNodes(1).build())
            .setConfigProp("listeners","EXTERNAL://127.0.0.1:0,CONTROLLER://127.0.0.1:0")
            .setConfigProp("listener.security.protocol.map","EXTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
            .setConfigProp("sasl.enabled.mechanisms","PLAIN,SCRAM-SHA-512")
            .setConfigProp("sasl.mechanism.inter.broker.protocol","PLAIN")
            .setConfigProp("listener.name.external.plain.sasl.jaas.config","org.apache.kafka.common.security.plain.PlainLoginModule required username=\"fixture_admin\" password=\""+adminPassword+"\" user_fixture_admin=\""+adminPassword+"\";")
            .setConfigProp("listener.name.external.scram-sha-512.sasl.jaas.config","org.apache.kafka.common.security.scram.ScramLoginModule required;")
            .setConfigProp("authorizer.class.name","org.apache.kafka.metadata.authorizer.StandardAuthorizer")
            .setConfigProp("allow.everyone.if.no.acl.found","false")
            .setConfigProp("super.users","User:fixture_admin;User:ANONYMOUS")
            .setConfigProp("offsets.topic.num.partitions",1).setConfigProp("log.index.size.max.bytes",65536).setConfigProp("log.segment.bytes",1048576).setConfigProp("offsets.topic.replication.factor",(short)1).setConfigProp("group.initial.rebalance.delay.ms",0).build();
        broker.format();broker.startup();broker.waitForReadyBrokers();
        brokerAddress=broker.bootstrapServers();
        }
        admin=Admin.create(login(operatorUser,operatorPassword,operatorMechanism));
        if(nativeIdentity!=null) {
            var meta=new Properties();try(var r=Files.newBufferedReader(project.resolve(".runtime/local/kafka/data/meta.properties"),StandardCharsets.UTF_8)){meta.load(r);}
            assertThat(admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS)).isEqualTo(meta.getProperty("cluster.id"));
        }
        admin.alterUserScramCredentials(List.of(
            new UserScramCredentialUpsertion(probe,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),probePassword.getBytes(StandardCharsets.UTF_8)),
            new UserScramCredentialUpsertion(writer,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),writerPassword.getBytes(StandardCharsets.UTF_8)),
            new UserScramCredentialUpsertion(reader,new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512,4096),readerPassword.getBytes(StandardCharsets.UTF_8)))).all().get(8,TimeUnit.SECONDS);
        admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(8,TimeUnit.SECONDS);
        admin.createAcls(List.of(acl(probe,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),
            acl(writer,ResourceType.TOPIC,topic,AclOperation.DESCRIBE),acl(writer,ResourceType.TOPIC,topic,AclOperation.WRITE),
            acl(writer,ResourceType.CLUSTER,"kafka-cluster",AclOperation.IDEMPOTENT_WRITE))).all().get(8,TimeUnit.SECONDS);
    }
    AclBinding acl(String user,ResourceType type,String name,AclOperation op){return new AclBinding(new ResourcePattern(type,name,PatternType.LITERAL),new AccessControlEntry("User:"+user,"*",op,AclPermissionType.ALLOW));}
    Map<String,String> baseEnv(){
        var result=new HashMap<String,String>();
        for(var key:List.of("SystemRoot","WINDIR","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA","PATH")){
            var value=System.getenv(key);if(value!=null)result.put(key,value);
        }return result;
    }
    Map<String,String> kafkaEnv(String prefix,String user,String password){
        return Map.of(prefix+"BOOTSTRAP_SERVERS",brokerAddress,prefix+"TOPIC",topic,
            prefix+"SECURITY_PROTOCOL","SASL_PLAINTEXT",prefix+"SASL_MECHANISM","SCRAM-SHA-512",prefix+"USERNAME",user,prefix+"PASSWORD",password);
    }
    Process service(String module,String main,Map<String,String> values)throws Exception {
        var classpath=project.resolve(module+"/target/classes")+java.io.File.pathSeparator+Files.readString(project.resolve(".runtime/"+module+"-kafka-live-classpath.txt")).strip();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms32m","-Xmx288m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=2","-XX:TieredStopAtLevel=1","-Dfile.encoding=UTF-8","-cp",classpath,main,"--logging.level.org.apache.kafka=ERROR");
        builder.directory(project.toFile());builder.environment().clear();builder.environment().putAll(baseEnv());builder.environment().putAll(values);
        builder.redirectErrorStream(true);builder.redirectOutput(evidence.resolve(module+".log").toFile());return builder.start();
    }
    void waitHealthy(Process process,String url)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<end){
            assertThat(process.isAlive()).as("Child service exited; inspect isolated service logs").isTrue();
            try{if(response(url,"GET",null,null).statusCode()==200)return;}catch(java.io.IOException ignored){}
            Thread.sleep(200);
        }fail("Child service startup timed out");
    }
    HttpResponse<String> response(String url,String method,Object body,Map<String,String> headers)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if(headers!=null)headers.forEach(request::header);
        if(csrf!=null&&(url.startsWith(foundation+"/api/v1")||receiver!=null&&url.startsWith(receiver+"/api/v1"))&&!Set.of("GET","HEAD").contains(method)&&!url.contains("/telemetry/reports")&&!url.contains("/access/check"))request.header(csrf.path("header_name").asText(),csrf.path("token").asText());
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    JsonNode data(String path,String method,Object body)throws Exception{
        var result=response(foundation+"/api/v1"+path,method,body,null);
        assertThat(result.statusCode()).as(method+" "+path).isEqualTo(200);
        var node=json.readTree(result.body());assertThat(node.path("code").asInt(-1)).as("API envelope").isZero();return node.path("data");
    }
    void workspaceLogin()throws Exception{workspaceLogin(workspaceName,workspacePassword);}
    void workspaceLogin(String name,String password)throws Exception{
        cookies.getCookieStore().removeAll();csrf=null;
        csrf=data("/auth/csrf","GET",null);
        var r=http.send(HttpRequest.newBuilder(URI.create(foundation+"/api/v1/auth/login")).header(csrf.path("header_name").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+name+"&password="+password)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);csrf=data("/auth/csrf","GET",null);
    }
    void provision()throws Exception {
        var uri=URI.create(env("PROVIDER_SOURCE_DB_URL").substring(5));
        assertThat(uri.getHost()).isEqualTo("127.0.0.1");assertThat(uri.getPath()).isEqualTo("/port_source_db");
        var source=data("/sources","POST",Map.of("source_name","KAFKA_LIVE_"+run,"source_type","MYSQL","host",uri.getHost(),"port",uri.getPort(),
            "database_name","port_source_db","username",env("PROVIDER_SOURCE_DB_USER"),"password",env("PROVIDER_SOURCE_DB_PASSWORD"),"ssl_mode","DISABLED"));
        sourceId=source.path("source_id").asText();credentialRef=source.path("credential_ref").asText();
        var scan=data("/sources/"+sourceId+"/scan","POST",null);
        var resource=data("/resources","POST",Map.of("resource_name","KAFKA_LIVE_RESOURCE_"+run,"source_id",sourceId,"scan_task_id",scan.path("task_id").asText(),
            "schema_name","port_source_db","physical_table","container_event","business_description","独立完整链路测试","owner_department","测试","security_level","内部","update_mode","实时"));
        resourceId=resource.path("resource_id").asText();data("/resources/"+resourceId+"/publish","POST",null);
        capabilityId=data("/capabilities","POST",Map.of("capability_name","KAFKA_LIVE_CAP_"+run,"resource_id",resourceId,"type","KAFKA_TOPIC","protocol","KAFKA","auth_type","SASL","topic",topic)).path("capability_id").asText();
    }
    long sourceCount()throws Exception{
        try(var c=DriverManager.getConnection(env("PROVIDER_SOURCE_DB_URL"),env("PROVIDER_SOURCE_DB_USER"),env("PROVIDER_SOURCE_DB_PASSWORD"));
            var statement=c.createStatement();var rs=statement.executeQuery("SELECT COUNT(*) FROM container_event")){rs.next();return rs.getLong(1);}
    }
    String sourceFingerprint()throws Exception{
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var c=DriverManager.getConnection(env("PROVIDER_SOURCE_DB_URL"),env("PROVIDER_SOURCE_DB_USER"),env("PROVIDER_SOURCE_DB_PASSWORD"));var s=c.createStatement();var r=s.executeQuery("SELECT * FROM container_event ORDER BY id")){
            while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++){
                String value=r.getString(i);var bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte)(value==null?0:1));digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    void simulateRemaining(JsonNode first)throws Exception{
        simulatedReceipts.add(Map.of("event_id",1,"partition",first.path("partition").asInt(),"offset",first.path("offset").asLong()));
        data("/capabilities/"+capabilityId+"/enable","POST",null);
        for(int id=2;id<=simulatedMessages;id++){
            var response=response(provider+"/containers/events/"+id+"/publish","POST",null,Map.of("X-Provider-Token",control,"X-Capability-Id",capabilityId));
            assertThat(response.statusCode()).as("Actual Provider simulation event "+id).isEqualTo(200);
            var receipt=json.readTree(response.body()).path("data");assertThat(receipt.path("topic").asText()).isEqualTo(topic);
            assertThat(receipt.path("partition").asInt()).isZero();assertThat(receipt.path("offset").asLong()).isEqualTo(id-1);
            simulatedReceipts.add(Map.of("event_id",id,"partition",receipt.path("partition").asInt(),"offset",receipt.path("offset").asLong()));
        }
        Files.writeString(evidence.resolve("simulation-receipts.json"),json.writeValueAsString(simulatedReceipts),StandardCharsets.UTF_8);
    }
    void consumeSimulated(KafkaConsumer<String,String> consumer)throws Exception{
        var ids=new HashSet<Long>();long bytes=0;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end&&ids.size()<simulatedMessages)for(var record:consumer.poll(Duration.ofMillis(100))){
            long id=json.readTree(record.value()).path("id").asLong();assertThat(id).isBetween(1L,(long)simulatedMessages);
            assertThat(ids.add(id)).as("No duplicate simulated event").isTrue();assertThat(record.partition()).isZero();assertThat(record.offset()).isEqualTo(id-1);
            bytes+=record.value().getBytes(StandardCharsets.UTF_8).length;
        }
        assertThat(ids).hasSize(simulatedMessages);
        if(simulatedBytes==0)simulatedBytes=bytes;else assertThat(bytes).isEqualTo(simulatedBytes);
    }
    void verifySimulationTelemetry()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);long records=0,bytes=0;
        while(System.nanoTime()<end){
            try(var c=db();var s=c.prepareStatement("SELECT COALESCE(SUM(record_count),0),COALESCE(SUM(byte_count),0) FROM sharing_runtime_event WHERE resource_id=? AND capability_id=? AND collection_point='KAFKA_PRODUCER' AND outcome='SUCCESS'")){
                s.setString(1,resourceId);s.setString(2,capabilityId);try(var r=s.executeQuery()){r.next();records=r.getLong(1);bytes=r.getLong(2);}
            }
            if(records==simulatedMessages)break;Thread.sleep(100);
        }
        assertThat(records).isEqualTo(simulatedMessages);assertThat(bytes).isEqualTo(simulatedBytes);
    }
    void browser()throws Exception{
        var config=evidence.resolve("browser-context.json");
        Files.writeString(config,json.writeValueAsString(Map.of("foundation",foundation,"provider",provider,"capability_id",capabilityId,"capability_name","KAFKA_LIVE_CAP_"+run,"evidence",evidence.toString())),StandardCharsets.UTF_8);
        var builder=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/kafka-chain-live.mjs").toString(),config.toString());
        builder.directory(project.resolve("foundation-web").toFile());builder.environment().clear();builder.environment().putAll(baseEnv());
        builder.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceName);builder.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspacePassword);
        builder.environment().put("KAFKA_LIVE_CONTROL_TOKEN",control);builder.environment().put("VITE_USE_MOCK","false");
        builder.redirectErrorStream(true);builder.redirectOutput(evidence.resolve("browser.log").toFile());browserProcess=builder.start();
        if(!browserProcess.waitFor(90,TimeUnit.SECONDS))fail("Browser timed out; inspect isolated browser.log");
        assertThat(browserProcess.exitValue()).as("Real Kafka browser flow; inspect isolated browser.log").isZero();
    }
    KafkaConsumer<String,String> consumer(){
        var config=login(reader,readerPassword,"SCRAM-SHA-512");
        config.put(ConsumerConfig.GROUP_ID_CONFIG,group);config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }
    JsonNode subscription(String name)throws Exception {
        var grant=data("/kafka-subscriptions","POST",Map.of("grant_name",name,"provider_subject_id",providerSubject,"demand_subject_id",demandSubject,
            "resource_id",resourceId,"capability_id",capabilityId,"purpose","真实隔离整主题测试","valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"valid_until",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"whole_topic_acknowledged",true,"reason","完整消息及可访问历史"));
        subscriptionIds.add(grant.path("grant_id").asText());
        grant=subscriptionAction(grant,"submit");return subscriptionAction(grant,"confirm");
    }
    JsonNode subscriptionAction(JsonNode grant,String action)throws Exception {
        // The reconciler may advance the version; never silently replay a stale user operation.
        var body=new HashMap<String,Object>();body.put("version",grant.path("version").asInt());body.put("reason","隔离测试"+action);
        if(Set.of("submit","confirm","return","revoke").contains(action))body.put("whole_topic_acknowledged",true);
        return data("/kafka-subscriptions/"+grant.path("grant_id").asText()+"/"+action,"POST",body);
    }
    JsonNode current(JsonNode grant)throws Exception{return data("/kafka-subscriptions/"+grant.path("grant_id").asText(),"GET",null);}
    JsonNode awaitState(JsonNode grant,String state)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);JsonNode row=null;
        while(System.nanoTime()<end){row=current(grant);if(state.equals(row.path("execution_state").asText()))return row;Thread.sleep(100);}
        fail("Execution state not reached: "+state);return row;
    }
    Collection<AclBinding> userAcls(String user)throws Exception {
        return admin.describeAcls(new AclBindingFilter(ResourcePatternFilter.ANY,new AccessControlEntryFilter("User:"+user,null,AclOperation.ANY,AclPermissionType.ANY))).values().get(5,TimeUnit.SECONDS);
    }
    KafkaConsumer<String,String> consumer(JsonNode delivery,String selectedTopic,String selectedGroup) {
        var props=login(delivery.path("username").asText(),delivery.path("password").asText(),"SCRAM-SHA-512");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,selectedGroup);props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        var c=new KafkaConsumer<String,String>(props);c.subscribe(List.of(selectedTopic));return c;
    }
    void expectDenied(KafkaConsumer<String,String> c)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);boolean denied=false;
        while(System.nanoTime()<end&&!denied)try{c.poll(Duration.ofMillis(100));}catch(org.apache.kafka.common.errors.AuthorizationException|org.apache.kafka.common.errors.AuthenticationException e){denied=true;}
        assertThat(denied).as("Actual Broker must deny subsequent reads").isTrue();
    }
    void noIdentity(JsonNode row)throws Exception {
        String user=row.path("broker_username").asText();assertThat(userAcls(user)).isEmpty();
        try{admin.describeUserScramCredentials(List.of(user)).description(user).get(5,TimeUnit.SECONDS);fail("Revoked SCRAM identity still exists");}
        catch(java.util.concurrent.ExecutionException e){assertThat(e.getCause()).isInstanceOf(org.apache.kafka.common.errors.ResourceNotFoundException.class);}
        assertThat(row.path("broker_access_confirmed").asBoolean()).isFalse();assertThat(row.path("credential_available").asBoolean()).isFalse();
    }
    void subscriptionBrowser(JsonNode grant)throws Exception {
        var config=evidence.resolve("subscription-browser-context.json");
        Files.writeString(config,json.writeValueAsString(Map.of("foundation",foundation,"grant_id",grant.path("grant_id").asText(),"grant_name",grant.path("grant_name").asText(),"evidence",evidence.toString())));
        var builder=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/kafka-subscription-chain-live.mjs").toString(),config.toString());
        builder.directory(project.resolve("foundation-web").toFile());builder.environment().clear();builder.environment().putAll(baseEnv());
        builder.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceName);builder.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspacePassword);
        builder.redirectErrorStream(true);builder.redirectOutput(evidence.resolve("subscription-browser.log").toFile());browserProcess=builder.start();
        assertThat(browserProcess.waitFor(90,TimeUnit.SECONDS)).as("Subscription browser timeout").isTrue();
        assertThat(browserProcess.exitValue()).as("Inspect subscription-browser.log").isZero();
    }
    void consumptionCli(JsonNode delivery)throws Exception {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms16m","-Xmx128m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=2","-Dfile.encoding=UTF-8","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),KafkaSubscriber.class.getName());
        var values=builder.environment();values.clear();values.putAll(baseEnv());
        values.put("KAFKA_DEMAND_BROKERS",brokerAddress);values.put("KAFKA_DEMAND_PROTOCOL","SASL_PLAINTEXT");values.put("KAFKA_DEMAND_MECHANISM","SCRAM-SHA-512");values.put("KAFKA_DEMAND_USERNAME",delivery.path("username").asText());values.put("KAFKA_DEMAND_PASSWORD",delivery.path("password").asText());
        values.put("KAFKA_DEMAND_TOPIC",topic);values.put("KAFKA_DEMAND_GROUP",delivery.path("consumer_group").asText());values.put("KAFKA_DEMAND_OFFSET_RESET","earliest");values.put("KAFKA_DEMAND_MAX_MESSAGES",String.valueOf(simulatedMessages));values.put("KAFKA_DEMAND_SECONDS","15");
        values.put("KAFKA_DEMAND_FOUNDATION_URL",foundation);values.put("KAFKA_DEMAND_GRANT_ID",delivery.path("grant").path("grant_id").asText());values.put("KAFKA_DEMAND_REPORT_TOKEN",delivery.path("consumption_token").asText());
        builder.redirectErrorStream(true);builder.redirectOutput(evidence.resolve("consumption-cli.log").toFile());
        var process=builder.start();try{assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("Actual external subscriber and report acknowledgement").isZero();}finally{stop(process);}
        String log=Files.readString(evidence.resolve("consumption-cli.log"));assertThat(log).contains("消费观察摘要已确认上报").doesNotContain(delivery.path("password").asText(),delivery.path("consumption_token").asText(),"container_no","event_time");
        var summary=data("/kafka-subscriptions/"+delivery.path("grant").path("grant_id").asText()+"/consumption","GET",null);
        assertThat(summary.path("summary").path("records").asLong()).isEqualTo(simulatedMessages);assertThat(summary.path("summary").path("bytes").asLong()).isEqualTo(simulatedBytes);assertThat(summary.toString()).doesNotContain(delivery.path("consumption_token").asText(),"digest","password");
        consumptionBrowser(delivery.path("grant").path("grant_id").asText());
    }
    void consumptionBrowser(String grantId)throws Exception {
        var config=evidence.resolve("consumption-browser-context.json");Files.writeString(config,json.writeValueAsString(Map.of("foundation",foundation,"grant_id",grantId,"evidence",evidence.toString(),"expected_messages",simulatedMessages,"expected_bytes",simulatedBytes)));
        var builder=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/kafka-consumption-live.mjs").toString(),config.toString()).directory(project.resolve("foundation-web").toFile());
        builder.environment().clear();builder.environment().putAll(baseEnv());builder.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceDemandName);builder.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspaceDemandPassword);
        builder.redirectErrorStream(true);builder.redirectOutput(evidence.resolve("consumption-browser.log").toFile());browserProcess=builder.start();assertThat(browserProcess.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(browserProcess.exitValue()).isZero();
    }
    void subscriptions()throws Exception {
        // Real published Source metadata and real Kafka validation, never inject ENABLED/UP success.
        if(!data("/capabilities/"+capabilityId,"GET",null).path("status").asText().equals("ENABLED"))data("/capabilities/"+capabilityId+"/enable","POST",null);
        for(String type:List.of("PROVIDER","DEMAND")){
            var s=data("/subjects","POST",Map.of("subject_code","KSUB_"+type+"_"+run.toUpperCase(Locale.ROOT),"subject_name","Kafka隔离"+type,"subject_type",type,"owner_department","测试单位","description","实际订阅测试"));
            String id=s.path("subject_id").asText();subjectIds.add(id);assertThat(s.path("status").asText()).isEqualTo("ENABLED");
            if(type.equals("PROVIDER"))providerSubject=id;else demandSubject=id;
        }
        data("/accounts","POST",Map.of("username",workspaceDemandName,"display_name","Kafka独立需求方账号","role","DEMAND","subject_id",demandSubject,"password",workspaceDemandPassword));
        var ui=subscription("Kafka浏览器_"+run);subscriptionBrowser(ui);noIdentity(current(ui));
        var first=subscription("Kafka实际读取_"+run);var second=subscription("Kafka独立授权_"+run);
        // A stale version never allocates a Broker principal.
        assertThat(response(foundation+"/api/v1/kafka-subscriptions/"+first.path("grant_id").asText()+"/provision","POST",Map.of("version",1,"reason","过期版本"),null).statusCode()).isEqualTo(409);
        first=subscriptionAction(first,"provision");second=subscriptionAction(second,"provision");
        assertThat(first.path("execution_state").asText()).isEqualTo("ACTIVE");assertThat(second.path("broker_username")).isNotEqualTo(first.path("broker_username"));
        assertThat(userAcls(first.path("broker_username").asText())).hasSize(3);
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        assertThat(response(foundation+"/api/v1/kafka-subscriptions/"+first.path("grant_id").asText()+"/provision","POST",Map.of("version",first.path("version").asInt(),"reason","普通用户不可配置"),null).statusCode()).isEqualTo(403);
        var credentialResponse=response(foundation+"/api/v1/kafka-subscriptions/"+first.path("grant_id").asText()+"/credential","POST",Map.of("version",first.path("version").asInt(),"reason","一次性领取"),null);
        assertThat(credentialResponse.statusCode()).isEqualTo(200);assertThat(credentialResponse.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var delivery=json.readTree(credentialResponse.body()).path("data");first=delivery.path("grant");
        assertThat(delivery.path("consumption_token").asText()).matches("rkc_[A-Za-z0-9_-]{43}");
        assertThat(current(first).toString()).doesNotContain(delivery.path("password").asText(),delivery.path("consumption_token").asText(),"encrypted_password","consumption_token_digest");
        assertThat(response(foundation+"/api/v1/kafka-subscriptions/"+first.path("grant_id").asText()+"/credential","POST",Map.of("version",first.path("version").asInt(),"reason","重复领取"),null).statusCode()).isEqualTo(400);
        try(var c=consumer(delivery,topic,delivery.path("consumer_group").asText())){
            consumeSimulated(c);verifySimulationTelemetry();
            first=subscriptionAction(first,"stop");assertThat(first.path("execution_state").asText()).isEqualTo("REVOKED");noIdentity(first);expectDenied(c);
        }
        // Exact group permission, even though the same topic is readable.
        workspaceLogin();
        var otherDelivery=data("/kafka-subscriptions/"+second.path("grant_id").asText()+"/credential","POST",Map.of("version",second.path("version").asInt(),"reason","独立身份领取"));second=otherDelivery.path("grant");
        try(var c=consumer(otherDelivery,topic,"forbidden-group")){expectDenied(c);}
        String unrelated=topic+"-unrelated";admin.createTopics(List.of(new NewTopic(unrelated,1,(short)1))).all().get(5,TimeUnit.SECONDS);
        try(var c=consumer(otherDelivery,unrelated,otherDelivery.path("consumer_group").asText())){expectDenied(c);}
        var writeProps=login(otherDelivery.path("username").asText(),otherDelivery.path("password").asText(),"SCRAM-SHA-512");
        writeProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,false);writeProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,org.apache.kafka.common.serialization.StringSerializer.class);
        writeProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,org.apache.kafka.common.serialization.StringSerializer.class);
        try(var producer=new KafkaProducer<String,String>(writeProps)){
            try{producer.send(new ProducerRecord<>(topic,"technical-denial-marker")).get(8,TimeUnit.SECONDS);fail("Subscriber was incorrectly allowed to write");}
            catch(java.util.concurrent.ExecutionException e){assertThat(e.getCause()).isInstanceOf(org.apache.kafka.common.errors.AuthorizationException.class);}
        }
        try(var c=consumer(otherDelivery,topic,otherDelivery.path("consumer_group").asText())){
            consumeSimulated(c);
        }
        assertThat(current(second).path("execution_state").asText()).isEqualTo("ACTIVE");
        consumptionCli(otherDelivery);
        second=subscriptionAction(second,"revoke");second=awaitState(second,"REVOKED");noIdentity(second);
        var denied=new ConsumptionReporter(foundation,second.path("grant_id").asText(),otherDelivery.path("consumption_token").asText());
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertThat(denied.send(new ConsumptionReporter.Batch(UUID.randomUUID().toString(),now.toString(),now.toString(),List.of(new ConsumptionReporter.Position(0,0,320))))).isFalse();
        String oldUser=first.path("broker_username").asText();first=subscriptionAction(first,"provision");
        assertThat(first.path("broker_username").asText()).isNotEqualTo(oldUser);first=subscriptionAction(first,"stop");noIdentity(first);
        // An externally incomplete ACL set is detected, reported and compensated, never called healthy.
        var partial=subscriptionAction(subscription("Kafka残缺权限_"+run),"provision");
        var one=userAcls(partial.path("broker_username").asText()).iterator().next();admin.deleteAcls(List.of(one.toFilter())).all().get(5,TimeUnit.SECONDS);
        partial=awaitState(partial,"REVOKED");noIdentity(partial);
        var partialEvents=data("/kafka-subscriptions/"+partial.path("grant_id").asText()+"/events","GET",null);
        assertThat(partialEvents.findValuesAsText("action")).contains("EXECUTION_FAILED","ACCESS_REVOKED");
        var future=subscription("Kafka未来有效_"+run);
        try(var c=db()){execute(c,"UPDATE kafka_subscription_grant SET valid_from=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR,valid_until=UTC_TIMESTAMP(3)+INTERVAL 2 HOUR WHERE grant_id=?",future.path("grant_id").asText());}
        assertThat(response(foundation+"/api/v1/kafka-subscriptions/"+future.path("grant_id").asText()+"/provision","POST",Map.of("version",future.path("version").asInt(),"reason","有效期未开始"),null).statusCode()).isEqualTo(400);
        assertThat(current(future).path("execution_state").asText()).isEqualTo("NOT_PROVISIONED");
        // Simulate external success followed by database history failure. The committed intent survives.
        var interrupted=subscription("Kafka执行恢复_"+run);String id=interrupted.path("grant_id").asText();
        try(var c=db()){execute(c,"INSERT INTO kafka_subscription_event(grant_id,action,from_status,to_status,whole_topic_acknowledged,operator_name,reason,version,occurred_at) VALUES(?,'TEST','APPROVED','APPROVED',false,'测试','故意制造执行历史唯一键冲突',5,UTC_TIMESTAMP(3))",id);}
        assertThat(response(foundation+"/api/v1/kafka-subscriptions/"+id+"/provision","POST",Map.of("version",3,"reason","恢复验证"),null).statusCode()).isEqualTo(500);
        var unresolved=current(interrupted);assertThat(unresolved.path("execution_state").asText()).isEqualTo("PENDING");assertThat(unresolved.path("version").asInt()).isEqualTo(4);
        String identity=unresolved.path("broker_username").asText();assertThat(userAcls(identity)).hasSize(3);
        try(var c=db()){execute(c,"DELETE FROM kafka_subscription_event WHERE grant_id=? AND version=5 AND action='TEST'",id);}
        interrupted=awaitState(interrupted,"ACTIVE");assertThat(interrupted.path("broker_username").asText()).isEqualTo(identity);assertThat(userAcls(identity)).hasSize(3);
        interrupted=subscriptionAction(interrupted,"stop");noIdentity(interrupted);
        // Cluster mismatch never removes permissions in a different cluster; visible failure is not success.
        var mismatch=subscriptionAction(subscription("Kafka集群核对_"+run),"provision");String correctCluster=admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS);
        try(var c=db()){execute(c,"UPDATE kafka_subscription_execution SET cluster_id='wrong-cluster-id' WHERE grant_id=?",mismatch.path("grant_id").asText());}
        mismatch=subscriptionAction(mismatch,"stop");assertThat(mismatch.path("execution_state").asText()).isEqualTo("FAILED");assertThat(mismatch.path("broker_access_confirmed").asBoolean()).isFalse();assertThat(userAcls(mismatch.path("broker_username").asText())).hasSize(3);
        try(var c=db()){execute(c,"UPDATE kafka_subscription_execution SET cluster_id=? WHERE grant_id=?",correctCluster,mismatch.path("grant_id").asText());}
        mismatch=awaitState(mismatch,"REVOKED");noIdentity(mismatch);
        // Automatic reconciliation, not a UI-only state change: all dependency and time invalidations.
        for(String dependency:List.of("expiry","source","capability","demand","provider","resource")){
            var grant=subscriptionAction(subscription("Kafka自动撤权_"+dependency+run),"provision");
            try(var c=db()){
                switch(dependency){
                    case "expiry" -> execute(c,"UPDATE kafka_subscription_grant SET valid_from=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,valid_until=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE grant_id=?",grant.path("grant_id").asText());
                    case "source" -> execute(c,"UPDATE source_system SET status='DISABLED' WHERE source_id=?",sourceId);
                    case "capability" -> execute(c,"UPDATE capability SET status='DISABLED' WHERE capability_id=?",capabilityId);
                    case "resource" -> execute(c,"UPDATE data_resource SET status='DRAFT' WHERE resource_id=?",resourceId);
                    default -> execute(c,"UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",dependency.equals("demand")?demandSubject:providerSubject);
                }
            }
            noIdentity(awaitState(grant,"REVOKED"));
            try(var c=db()){
                execute(c,"UPDATE source_system SET status='ENABLED' WHERE source_id=?",sourceId);execute(c,"UPDATE capability SET status='ENABLED' WHERE capability_id=?",capabilityId);
                execute(c,"UPDATE data_resource SET status='PUBLISHED' WHERE resource_id=?",resourceId);execute(c,"UPDATE sharing_subject SET status='ENABLED' WHERE subject_id IN (?,?)",providerSubject,demandSubject);
            }
        }
        // No credential can appear in persisted non-secret state or event history.
        assertThat(data("/kafka-subscriptions/"+id+"/events","GET",null).toString()).doesNotContain(delivery.path("password").asText(),otherDelivery.path("password").asText());
    }
    void restRegression()throws Exception{
        // Only the independent test registration changes; never the original resource or business rows.
        var fields=data("/resources/"+resourceId+"/fields","GET",null);
        JsonNode time=null;for(var f:fields)if(f.path("field_name").asText().equals("event_time"))time=f;
        assertThat(time).isNotNull();
        data("/resources/"+resourceId+"/fields/event_time","PUT",Map.of("time_field",true,"resource_version",time.path("resource_version").asInt(),"sensitivity_level","内部"));
        restCapability=data("/capabilities","POST",Map.of("capability_name","REST_ROLE_LIVE_"+run,"resource_id",resourceId,"type","REST_API","http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE")).path("capability_id").asText();
        data("/capabilities/"+restCapability+"/verify","POST",null);data("/capabilities/"+restCapability+"/enable","POST",null);
        var relation=data("/relationships","POST",Map.of("relationship_name","REST_ROLE_LIVE_"+run,"provider_subject_id",providerSubject,"demand_subject_id",demandSubject,"resource_id",resourceId,"capability_id",restCapability,"purpose","真实会话与外部调用隔离回归","valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"valid_until",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","独立登记","policy",Map.of("allowed_fields",List.of("container_no","event_time"),"time_field","event_time","data_from","2026-09-20T00:00:00Z","data_until","2026-09-21T00:00:00Z")));
        restRelationship=relation.path("relationship_id").asText();restPolicy=relation.path("policy").path("policy_id").asText();
        for(String action:List.of("submit","confirm","enable"))relation=data("/relationships/"+restRelationship+"/"+action,"POST",Map.of("version",relation.path("version").asInt(),"reason","独立REST回归"));
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        var issued=data("/subjects/"+demandSubject+"/credentials","POST",Map.of("credential_name","REST_ROLE_LIVE_"+run,"expires_at",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","本主体外部调用"));
        restToken=issued.path("token").asText();assertThat(restToken).startsWith("rdf_");
        // A separate external caller carries no management Cookie or provider operations token.
        var external=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var discovery=external.send(HttpRequest.newBuilder(URI.create(foundation+"/api/v1/capabilities/"+restCapability+"/invocation")).header("Authorization","Bearer "+restToken).header("X-Relationship-Id",restRelationship).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).isEqualTo(200);assertThat(json.readTree(discovery.body()).path("code").asInt()).isZero();
        assertThat(externalRest(external).statusCode()).isEqualTo(200);
        var rows=json.readTree(externalRest(external).body()).path("data");assertThat(rows.isArray()).isTrue();assertThat(rows.size()).isGreaterThan(0);
        for(var row:rows){var keys=new HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertThat(keys).containsExactlyInAnyOrder("container_no","event_time");}
        data("/caller-credentials/"+issued.path("credential").path("credential_id").asText()+"/revoke","POST",Map.of("version",issued.path("credential").path("version").asInt(),"reason","真实撤销回归"));
        assertThat(externalRest(external).statusCode()).isEqualTo(401);
        issued=data("/subjects/"+demandSubject+"/credentials","POST",Map.of("credential_name","REST_ROLE_REISSUE_"+run,"expires_at",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","本主体重新签发"));restToken=issued.path("token").asText();
        workspaceLogin();
        relation=data("/relationships/"+restRelationship+"/suspend","POST",Map.of("version",relation.path("version").asInt(),"reason","暂停拒绝"));
        assertThat(externalRest(external).statusCode()).isEqualTo(403);
        data("/relationships/"+restRelationship+"/enable","POST",Map.of("version",relation.path("version").asInt(),"reason","恢复独立测试"));
        assertThat(externalRest(external).statusCode()).isEqualTo(200);
    }
    void ownedProviderConfirmation()throws Exception{
        workspaceLogin();
        data("/accounts","POST",Map.of("username",workspaceProviderName,"display_name","独立提供方确认验收","role","PROVIDER","subject_id",providerSubject,"password",workspaceProviderPassword));
        // Test-only ownership in the isolated schema. No development registration or business row is reassigned.
        try(var c=db()){execute(c,"UPDATE source_system SET provider_subject_id=? WHERE source_id=? AND source_name=?",providerSubject,sourceId,"KAFKA_LIVE_"+run);}
        var ownedResourceBefore=data("/resources/"+resourceId,"GET",null);var ownedFieldsBefore=data("/resources/"+resourceId+"/fields","GET",null);var ownedCapabilityBefore=data("/capabilities/"+capabilityId,"GET",null);
        var grant=data("/kafka-subscriptions","POST",Map.of("grant_name","提供方确认真实授权_"+run,"provider_subject_id",providerSubject,"demand_subject_id",demandSubject,"resource_id",resourceId,"capability_id",capabilityId,"purpose","独立真实Broker确认闭环","valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"valid_until",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"whole_topic_acknowledged",true,"reason","独立登记"));
        subscriptionIds.add(grant.path("grant_id").asText());String path="/kafka-subscriptions/"+grant.path("grant_id").asText();
        assertThat(grant.path("provider_review_mode").asText()).isEqualTo("REQUIRED");
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);grant=subscriptionAction(grant,"submit");
        workspaceLogin();assertThat(response(foundation+"/api/v1"+path+"/confirm","POST",Map.of("version",grant.path("version").asInt(),"reason","不能跳过提供方","whole_topic_acknowledged",true),null).statusCode()).isEqualTo(400);
        workspaceLogin(workspaceProviderName,workspaceProviderPassword);
        grant=data(path+"/provider-confirm","POST",Map.of("version",grant.path("version").asInt(),"reason","提供方真实核对用途、有效期、整主题历史范围","content_acknowledged",true));
        assertThat(grant.path("status").asText()).isEqualTo("PENDING");assertThat(grant.path("provider_confirmed_at").isNull()).isFalse();
        var events=data(path+"/events","GET",null);assertThat(events.get(2).path("operator_name").asText()).isEqualTo(workspaceProviderName);
        assertThat(response(foundation+"/api/v1"+path+"/confirm","POST",Map.of("version",grant.path("version").asInt(),"reason","提供方不能最终审批","whole_topic_acknowledged",true),null).statusCode()).isEqualTo(403);
        workspaceLogin();grant=subscriptionAction(grant,"confirm");grant=subscriptionAction(grant,"provision");assertThat(grant.path("broker_access_confirmed").asBoolean()).isTrue();
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);var delivery=data(path+"/credential","POST",Map.of("version",grant.path("version").asInt(),"reason","本主体领取"));
        formalReceiving(delivery);
        workspaceLogin();grant=current(grant);grant=subscriptionAction(grant,"stop");assertThat(grant.path("execution_state").asText()).isEqualTo("REVOKED");noIdentity(grant);
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        long receiverDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JsonNode receiverState=null;while(System.nanoTime()<receiverDeadline){receiverState=received("/receiving-tasks/"+receiverTask,"GET",null);if(receiverState.path("runtime_state").asText().equals("BLOCKED"))break;Thread.sleep(200);}assertThat(receiverState.path("runtime_state").asText()).isEqualTo("BLOCKED");
        assertThat(response(receiver+"/api/v1/receiving-tasks/"+receiverTask+"/progress","GET",null,null).statusCode()).isEqualTo(403);
        var historicalExport=response(receiver+"/api/v1/receiving-tasks/"+receiverTask+"/messages/export","GET",null,null);assertThat(historicalExport.statusCode()).isEqualTo(200);assertThat(historicalExport.body().lines().count()).isEqualTo(simulatedMessages+3);
        var historicalLookup=received("/receiving-tasks?grantId="+grant.path("grant_id").asText(),"GET",null);assertThat(historicalLookup.path("total").asInt()).isEqualTo(1);assertThat(historicalLookup.path("items").get(0).path("task_id").asText()).isEqualTo(receiverTask);
        receivingHistoryBrowser();
        workspaceLogin();
        try(var client=consumer(delivery,topic,delivery.path("consumer_group").asText())){expectDenied(client);}
        var relation=data("/relationships","POST",Map.of("relationship_name","提供方确认REST_"+run,"provider_subject_id",providerSubject,"demand_subject_id",demandSubject,"resource_id",resourceId,"capability_id",restCapability,"purpose","提供方确认后才允许REST访问","valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"valid_until",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","独立登记","policy",Map.of("allowed_fields",List.of("container_no","event_time"),"time_field","event_time","data_from","2026-09-20T00:00:00Z","data_until","2026-09-21T00:00:00Z")));
        ownedRestRelationship=relation.path("relationship_id").asText();ownedRestPolicy=relation.path("policy").path("policy_id").asText();String restPath="/relationships/"+ownedRestRelationship;
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);relation=data(restPath+"/submit","POST",Map.of("version",1,"reason","需求方提交"));
        assertThat(ownedExternalRest().statusCode()).isEqualTo(403);
        workspaceLogin();assertThat(response(foundation+"/api/v1"+restPath+"/confirm","POST",Map.of("version",2,"reason","管理员不能跳过提供方"),null).statusCode()).isEqualTo(400);
        workspaceLogin(workspaceProviderName,workspaceProviderPassword);relation=data(restPath+"/provider-confirm","POST",Map.of("version",2,"reason","提供方确认字段与时间范围","content_acknowledged",true));
        assertThat(ownedExternalRest().statusCode()).isEqualTo(403);
        workspaceLogin();relation=data(restPath+"/confirm","POST",Map.of("version",relation.path("version").asInt(),"reason","管理员最终审批"));relation=data(restPath+"/enable","POST",Map.of("version",relation.path("version").asInt(),"reason","启用共享"));
        var result=ownedExternalRest();assertThat(result.statusCode()).isEqualTo(200);
        for(var row:json.readTree(result.body()).path("data")){var keys=new HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertThat(keys).containsExactlyInAnyOrder("container_no","event_time");}
        data(restPath+"/revoke","POST",Map.of("version",relation.path("version").asInt(),"reason","撤回独立授权"));assertThat(ownedExternalRest().statusCode()).isEqualTo(403);
        assertThat(data("/resources/"+resourceId,"GET",null)).isEqualTo(ownedResourceBefore);assertThat(data("/resources/"+resourceId+"/fields","GET",null)).isEqualTo(ownedFieldsBefore);assertThat(data("/capabilities/"+capabilityId,"GET",null)).isEqualTo(ownedCapabilityBefore);
    }
    Connection receiverDb()throws Exception{var c=DriverManager.getConnection(env("KAFKA_LIVE_DEMAND_DB_URL"),env("DEMAND_DB_USER"),env("DEMAND_DB_PASSWORD"));assertThat(c.getCatalog()).isEqualTo("demand_receiver_live_test");return c;}
    JsonNode received(String path,String method,Object body)throws Exception{var response=response(receiver+"/api/v1"+path,method,body,null);assertThat(response.statusCode()).as("Receiver "+method+" "+path).isEqualTo(200);return json.readTree(response.body()).path("data");}
    void cleanupReceiver()throws Exception{if(receiverTask!=null)try(var c=receiverDb()){c.setAutoCommit(false);try{for(String table:List.of("receiving_outbox","receiving_checkpoint","received_message","receiving_task"))execute(c,"DELETE FROM "+table+" WHERE task_id=?",receiverTask);c.commit();}catch(Exception e){c.rollback();throw e;}}}
    void formalReceiving(JsonNode delivery)throws Exception{
        int receiverPort=port();receiver="http://127.0.0.1:"+receiverPort;
        receiverEnvironment=new HashMap<>(Map.of("DEMAND_BIND","127.0.0.1","DEMAND_PORT",String.valueOf(receiverPort),"DEMAND_DB_NAME","demand_receiver_live_test","DEMAND_DB_URL",env("KAFKA_LIVE_DEMAND_DB_URL"),"DEMAND_DB_USER",env("DEMAND_DB_USER"),"DEMAND_DB_PASSWORD",env("DEMAND_DB_PASSWORD"),"DEMAND_CREDENTIAL_KEY",env("DEMAND_CREDENTIAL_KEY"),"DEMAND_FOUNDATION_URL",foundation));
        receiverEnvironment.put("DEMAND_LOG_FILE",evidence.resolve("demand-runtime.log").toString());
        receiverProcess=service("demand-service","cn.railway.demand.DemandApplication",receiverEnvironment);waitHealthy(receiverProcess,receiver+"/api/v1/demand/health");
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(receiver+"/api/v1/receiving-tasks")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        var body=new HashMap<String,Object>(Map.of("task_name","真实接收_"+run,"grant_id",delivery.path("grant").path("grant_id").asText(),"password",delivery.path("password").asText(),"consumption_token",delivery.path("consumption_token").asText(),"start_position","EARLIEST"));
        var bad=new HashMap<>(body);bad.put("password","ks_"+"x".repeat(43));assertThat(response(receiver+"/api/v1/receiving-tasks","POST",bad,null).statusCode()).isEqualTo(400);
        var task=received("/receiving-tasks","POST",body);receiverTask=task.path("task_id").asText();String path="/receiving-tasks/"+receiverTask;
        var receiverManifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(evidence.resolve("test-manifest.json")));receiverManifest.put("receiver_task",receiverTask);receiverManifest.put("receiver_database","demand_receiver_live_test");receiverManifest.put("receiver_fault_constraint","receiver_fault_"+run);receiverManifest.put("other_demand_account",workspaceOtherDemandName);Files.writeString(evidence.resolve("test-manifest.json"),receiverManifest.toString());
        assertThat(task.toString()).doesNotContain(delivery.path("password").asText(),delivery.path("consumption_token").asText());
        assertThat(response(receiver+"/api/v1/receiving-tasks","POST",body,null).statusCode()).isEqualTo(409);
        workspaceLogin();var otherSubject=data("/subjects","POST",Map.of("subject_code","RECEIVER_B_"+run.toUpperCase(Locale.ROOT),"subject_name","隔离需求方B","subject_type","DEMAND","owner_department","测试单位","description","主体隔离"));subjectIds.add(otherSubject.path("subject_id").asText());
        data("/accounts","POST",Map.of("username",workspaceOtherDemandName,"display_name","隔离需求方B","role","DEMAND","subject_id",otherSubject.path("subject_id").asText(),"password",workspaceOtherDemandPassword));
        workspaceLogin(workspaceOtherDemandName,workspaceOtherDemandPassword);assertThat(received("/receiving-tasks","GET",null).path("total").asLong()).isZero();
        var otherLookup=received("/receiving-tasks?grantId="+delivery.path("grant").path("grant_id").asText(),"GET",null);assertThat(otherLookup.path("total").asLong()).isZero();assertThat(otherLookup.path("items").size()).isZero();
        for(String tail:List.of("","/messages","/progress","/messages/export","/messages/1/download"))assertThat(response(receiver+"/api/v1"+path+tail,"GET",null,null).statusCode()).isEqualTo(403);
        assertThat(response(receiver+"/api/v1"+path+"/start","POST",Map.of("version",1),null).statusCode()).isEqualTo(403);
        assertThat(response(receiver+"/api/v1/receiving-tasks","POST",body,null).statusCode()).isEqualTo(403);
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        assertThat(response(receiver+"/api/v1"+path+"/start","POST",Map.of("version",99),null).statusCode()).isEqualTo(409);
        // Actual CSRF validation is performed by the existing Foundation security chain.
        String cookie=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).map(c->c.getName()+"="+c.getValue()).findFirst().orElseThrow();
        var noCsrf=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(receiver+"/api/v1"+path+"/start")).header("Cookie",cookie).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"version\":1}")).build(),HttpResponse.BodyHandlers.discarding());assertThat(noCsrf.statusCode()).isEqualTo(403);
        received(path+"/start","POST",Map.of("version",1));
        // User logout must not cancel the durable receiving task.
        data("/auth/logout","POST",null);csrf=null;cookies.getCookieStore().removeAll();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);long count=0;
        while(System.nanoTime()<deadline){try(var c=receiverDb();var s=c.prepareStatement("SELECT COUNT(*) FROM received_message WHERE task_id=?")){s.setString(1,receiverTask);try(var r=s.executeQuery()){r.next();count=r.getLong(1);}}if(count==simulatedMessages)break;Thread.sleep(200);}
        assertThat(count).as("Real durable reception continues after logout").isEqualTo(simulatedMessages);
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        var page=received(path+"/messages?limit=1&offset=0","GET",null);assertThat(page.path("total").asInt()).isEqualTo(simulatedMessages);
        var message=received(path+"/messages/"+page.path("items").get(0).path("message_id").asText(),"GET",null);assertThat(message.path("message_value").path("encoding").asText()).isEqualTo("UTF8");
        JsonNode event=json.readTree(message.path("message_value").path("content").asText());assertThat(event.path("container_no").asText()).isNotBlank();
        try(var source=DriverManager.getConnection(env("PROVIDER_SOURCE_DB_URL"),env("PROVIDER_SOURCE_DB_USER"),env("PROVIDER_SOURCE_DB_PASSWORD"));var s=source.prepareStatement("SELECT container_no FROM container_event WHERE id=?")){s.setLong(1,event.path("id").asLong());try(var r=s.executeQuery()){assertThat(r.next()).isTrue();assertThat(event.path("container_no").asText()).isEqualTo(r.getString(1));}}
        // Process restart preserves data and resumes the DB checkpoint without duplicating it.
        stop(receiverProcess);receiverProcess=service("demand-service","cn.railway.demand.DemandApplication",receiverEnvironment);waitHealthy(receiverProcess,receiver+"/api/v1/demand/health");
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<deadline){task=received(path,"GET",null);if(task.path("runtime_state").asText().equals("RUNNING"))break;Thread.sleep(200);}assertThat(task.path("runtime_state").asText()).isEqualTo("RUNNING");
        assertThat(received(path,"GET",null).path("received_count").asInt()).isEqualTo(simulatedMessages);
        // Inject an isolated DB write failure for the next Kafka position only.
        String fault="receiver_fault_"+run;assertThat(receiverTask).matches("RCV_[a-f0-9]{32}");
        try(var c=receiverDb();var s=c.createStatement()){s.execute("ALTER TABLE received_message ADD CONSTRAINT "+fault+" CHECK (task_id <> '"+receiverTask+"' OR message_offset < "+simulatedMessages+")");}
        try{
            productionBrowser(); // Real provider login: single send plus two selected records, no operator token in browser.
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<deadline){task=received(path,"GET",null);if(task.path("runtime_state").asText().equals("RETRYING"))break;Thread.sleep(100);}assertThat(task.path("runtime_state").asText()).isEqualTo("RETRYING");assertThat(task.path("received_count").asInt()).isEqualTo(simulatedMessages);
            try(var subscriber=consumer(delivery,topic,delivery.path("consumer_group").asText())){var committed=subscriber.committed(Set.of(new org.apache.kafka.common.TopicPartition(topic,0)),Duration.ofSeconds(5));assertThat(committed.get(new org.apache.kafka.common.TopicPartition(topic,0)).offset()).isEqualTo(simulatedMessages);}
        }finally{try(var c=receiverDb();var s=c.createStatement()){s.execute("ALTER TABLE received_message DROP CHECK "+fault);}}
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<deadline){count=received(path,"GET",null).path("received_count").asLong();if(count==simulatedMessages+3)break;Thread.sleep(200);}assertThat(count).isEqualTo(simulatedMessages+3);
        try(var subscriber=consumer(delivery,topic,delivery.path("consumer_group").asText())){var committed=subscriber.committed(Set.of(new org.apache.kafka.common.TopicPartition(topic,0)),Duration.ofSeconds(5));assertThat(committed.get(new org.apache.kafka.common.TopicPartition(topic,0)).offset()).isEqualTo(simulatedMessages+3);}
        task=received(path,"GET",null);received(path+"/stop","POST",Map.of("version",task.path("version").asInt()));
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){task=received(path,"GET",null);if(task.path("runtime_state").asText().equals("STOPPED"))break;Thread.sleep(200);}assertThat(task.path("runtime_state").asText()).isEqualTo("STOPPED");
        received(path+"/resume","POST",Map.of("version",task.path("version").asInt()));
        receivingBrowser();
        var taskBeforeProgress=received(path,"GET",null);
        var progressResult=received(path+"/progress","GET",null);assertThat(progressResult.path("summary").path("remaining_positions").asLong()).isZero();assertThat(progressResult.path("summary").path("status").asText()).isEqualTo("CAUGHT_UP");
        assertThat(progressResult.toString()).doesNotContain(delivery.path("password").asText(),delivery.path("consumption_token").asText(),"encrypted_secrets","context_json","container_no");
        assertThat(received(path,"GET",null).path("version")).isEqualTo(taskBeforeProgress.path("version"));assertThat(received(path,"GET",null).path("received_count")).isEqualTo(taskBeforeProgress.path("received_count"));
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JsonNode observed=null;while(System.nanoTime()<deadline){observed=data("/kafka-subscriptions/"+delivery.path("grant").path("grant_id").asText()+"/consumption","GET",null);if(observed.path("summary").path("records").asLong()==count)break;Thread.sleep(200);}assertThat(observed.path("summary").path("records").asLong()).as("Durable outbox confirmed by actual Foundation").isEqualTo(count);
        workspaceLogin(workspaceProviderName,workspaceProviderPassword);assertThat(response(receiver+"/api/v1/receiving-tasks","GET",null,null).statusCode()).isEqualTo(403);assertThat(response(receiver+"/api/v1"+path+"/progress","GET",null,null).statusCode()).isEqualTo(403);
        assertThat(response(receiver+"/api/v1"+path+"/messages/export","GET",null,null).statusCode()).isEqualTo(403);
        workspaceLogin();assertThat(response(receiver+"/api/v1/receiving-tasks","GET",null,null).statusCode()).isEqualTo(403);assertThat(response(receiver+"/api/v1"+path+"/progress","GET",null,null).statusCode()).isEqualTo(403);
        assertThat(response(receiver+"/api/v1"+path+"/messages/export","GET",null,null).statusCode()).isEqualTo(403);
        workspaceLogin(workspaceDemandName,workspaceDemandPassword);
        Files.writeString(evidence.resolve("receiver-verification.json"),json.writeValueAsString(Map.of("real_messages_persisted",count,"source_payload_matched",true,"continued_after_logout",true,"restart_preserved",true,"broker_position_committed",true,"csrf_enforced",true,"no_duplicate_after_restart",true,"database_failure_did_not_commit",true,"subject_isolation",true)));
        try(var c=receiverDb();var s=c.prepareStatement("SELECT COUNT(*) FROM receiving_outbox WHERE task_id=? AND report_state<>'SENT'")){s.setString(1,receiverTask);try(var r=s.executeQuery()){r.next();assertThat(r.getLong(1)).isZero();}}
    }
    void receivingBrowser()throws Exception{
        var config=evidence.resolve("receiver-browser-context.json");Files.writeString(config,json.writeValueAsString(Map.of("foundation",foundation,"receiver",receiver,"task_id",receiverTask,"evidence",evidence.toString(),"expected_messages",simulatedMessages)));
        var b=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/receiving-live.mjs").toString(),config.toString()).directory(project.resolve("foundation-web").toFile());b.environment().clear();b.environment().putAll(baseEnv());b.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceDemandName);b.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspaceDemandPassword);b.redirectErrorStream(true);b.redirectOutput(evidence.resolve("receiver-browser.log").toFile());browserProcess=b.start();assertThat(browserProcess.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(browserProcess.exitValue()).as("Real receiver UI").isZero();
    }
    void productionBrowser()throws Exception{
        var config=evidence.resolve("production-browser-context.json");Files.writeString(config,json.writeValueAsString(Map.of("foundation",foundation,"provider",provider,"capability",capabilityId,"evidence",evidence.toString())));
        var b=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/production-live.mjs").toString(),config.toString()).directory(project.resolve("foundation-web").toFile());b.environment().clear();b.environment().putAll(baseEnv());b.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceProviderName);b.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspaceProviderPassword);b.redirectErrorStream(true);b.redirectOutput(evidence.resolve("production-browser.log").toFile());browserProcess=b.start();assertThat(browserProcess.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(browserProcess.exitValue()).as("Real provider production UI").isZero();
    }
    void receivingHistoryBrowser()throws Exception{
        var config=evidence.resolve("receiver-browser-context.json");
        var b=new ProcessBuilder(env("KAFKA_LIVE_NODE"),project.resolve("foundation-web/scripts/receiving-history-entry-live.mjs").toString(),config.toString()).directory(project.resolve("foundation-web").toFile());b.environment().clear();b.environment().putAll(baseEnv());b.environment().put("KAFKA_LIVE_WORKSPACE_USER",workspaceDemandName);b.environment().put("KAFKA_LIVE_WORKSPACE_PASSWORD",workspaceDemandPassword);b.redirectErrorStream(true);b.redirectOutput(evidence.resolve("receiver-history-entry-browser.log").toFile());browserProcess=b.start();assertThat(browserProcess.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(browserProcess.exitValue()).as("Revoked grant opens retained receiver data").isZero();
    }
    HttpResponse<String> ownedExternalRest()throws Exception{
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(provider+"/containers/events?limit=3")).header("Authorization","Bearer "+restToken).header("X-Relationship-Id",ownedRestRelationship).header("X-Capability-Id",restCapability).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> externalRest(HttpClient external)throws Exception{
        return external.send(HttpRequest.newBuilder(URI.create(provider+"/containers/events?limit=3")).header("Authorization","Bearer "+restToken).header("X-Relationship-Id",restRelationship).header("X-Capability-Id",restCapability).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    void execute(Connection c,String sql,Object...args)throws Exception{try(var st=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);st.executeUpdate();}}
    void cleanup()throws Exception{
        try(var c=db()){
            c.setAutoCommit(false);
            try{
                // Every delete is anchored to this random test identity in the isolated schema.
                for(String grant:subscriptionIds){execute(c,"DELETE FROM kafka_consumption_report WHERE grant_id=?",grant);execute(c,"DELETE FROM kafka_subscription_execution WHERE grant_id=?",grant);execute(c,"DELETE FROM kafka_subscription_event WHERE grant_id=?",grant);execute(c,"DELETE FROM kafka_subscription_grant WHERE grant_id=?",grant);}
                if(restRelationship!=null){execute(c,"DELETE FROM sharing_relationship_event WHERE relationship_id=?",restRelationship);execute(c,"DELETE FROM sharing_relationship WHERE relationship_id=?",restRelationship);}
                if(restPolicy!=null)execute(c,"DELETE FROM access_policy WHERE policy_id=?",restPolicy);
                if(ownedRestRelationship!=null){execute(c,"DELETE FROM sharing_relationship_event WHERE relationship_id=?",ownedRestRelationship);execute(c,"DELETE FROM sharing_relationship WHERE relationship_id=?",ownedRestRelationship);}
                if(ownedRestPolicy!=null)execute(c,"DELETE FROM access_policy WHERE policy_id=?",ownedRestPolicy);
                if(demandSubject!=null){execute(c,"DELETE e FROM caller_credential_event e JOIN caller_credential c ON e.credential_id=c.credential_id WHERE c.subject_id=?",demandSubject);execute(c,"DELETE FROM caller_credential WHERE subject_id=?",demandSubject);}
                if(restCapability!=null)execute(c,"DELETE FROM capability WHERE capability_id=? AND resource_id=?",restCapability,resourceId);
                execute(c,"DELETE FROM workspace_account WHERE username IN (?,?,?,?)",workspaceName,workspaceDemandName,workspaceProviderName,workspaceOtherDemandName);
                if(capabilityId!=null)execute(c,"DELETE FROM capability WHERE capability_id=? AND capability_name=? AND resource_id=?",capabilityId,"KAFKA_LIVE_CAP_"+run,resourceId);
                if(resourceId!=null){
                    execute(c,"DELETE FROM resource_field WHERE resource_id=? AND EXISTS(SELECT 1 FROM data_resource WHERE resource_id=? AND resource_name=?)",resourceId,resourceId,"KAFKA_LIVE_RESOURCE_"+run);
                    execute(c,"DELETE FROM data_resource WHERE resource_id=? AND source_id=? AND resource_name=?",resourceId,sourceId,"KAFKA_LIVE_RESOURCE_"+run);
                }
                if(sourceId!=null){
                    execute(c,"DELETE FROM metadata_scan_field WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",sourceId);
                    execute(c,"DELETE FROM metadata_scan_table WHERE task_id IN (SELECT task_id FROM metadata_scan_task WHERE source_id=?)",sourceId);
                    execute(c,"DELETE FROM metadata_scan_task WHERE source_id=?",sourceId);
                    execute(c,"DELETE FROM source_system WHERE source_id=? AND source_name=?",sourceId,"KAFKA_LIVE_"+run);
                    if(credentialRef!=null)execute(c,"DELETE FROM source_credential WHERE credential_ref=? AND NOT EXISTS(SELECT 1 FROM source_system WHERE credential_ref=?)",credentialRef,credentialRef);
                }
                for(String subject:subjectIds)execute(c,"DELETE FROM sharing_subject WHERE subject_id=?",subject);
                execute(c,"DELETE FROM sharing_runtime_event WHERE collector_id=?","kafka-live-"+run);
                execute(c,"DELETE FROM sharing_runtime_report WHERE collector_id=?","kafka-live-"+run);
                execute(c,"DELETE FROM workspace_account WHERE username=?",workspaceName);
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    void stop(Process process)throws Exception{if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}}}

    void cleanupNativeBroker()throws Exception {
        if(nativeIdentity==null||admin==null)return;
        var users=new HashSet<>(List.of(probe,writer,reader));
        try(var c=db()) {
            for(String grant:subscriptionIds)try(var s=c.prepareStatement("SELECT broker_username FROM kafka_subscription_execution WHERE grant_id=?")){s.setString(1,grant);try(var r=s.executeQuery()){if(r.next()&&r.getString(1)!=null)users.add(r.getString(1));}}
        }
        for(String user:users) {
            var filter=new AclBindingFilter(ResourcePatternFilter.ANY,new AccessControlEntryFilter("User:"+user,null,AclOperation.ANY,AclPermissionType.ANY));
            admin.deleteAcls(List.of(filter)).all().get(5,TimeUnit.SECONDS);
            try{var credentials=admin.describeUserScramCredentials(List.of(user)).description(user).get(5,TimeUnit.SECONDS).credentialInfos();
                if(!credentials.isEmpty())admin.alterUserScramCredentials(credentials.stream().<UserScramCredentialAlteration>map(i->new UserScramCredentialDeletion(user,i.mechanism())).toList()).all().get(5,TimeUnit.SECONDS);
            }catch(java.util.concurrent.ExecutionException ex){if(!(ex.getCause() instanceof org.apache.kafka.common.errors.ResourceNotFoundException))throw ex;}
            assertThat(admin.describeAcls(filter).values().get(5,TimeUnit.SECONDS)).isEmpty();
        }
        if(System.getProperty("os.name").startsWith("Windows"))Files.writeString(evidence.resolve("native-retained-topic.txt"),topic+"\n",StandardCharsets.UTF_8);
        else if(admin.listTopics().names().get(5,TimeUnit.SECONDS).contains(topic))admin.deleteTopics(List.of(topic)).all().get(10,TimeUnit.SECONDS);
    }

    @Test void actualServicesBrowserScramMessageReceiptAndTelemetry()throws Exception{
        project=Path.of(env("KAFKA_LIVE_PROJECT")).toAbsolutePath().normalize();evidence=Files.createTempDirectory(project.resolve(".runtime"),"kafka-chain-");
        // Save only non-secret identities before side effects, so an interrupted run can be cleaned by exact names.
        Files.writeString(evidence.resolve("test-manifest.json"),json.writeValueAsString(Map.of("run",run,"topic",topic,"probe",probe,"writer",writer,"reader",reader,"group",group,"workspace_admin",workspaceName,"workspace_demand",workspaceDemandName,"workspace_provider",workspaceProviderName,"schema","foundation_kafka_live_test")),StandardCharsets.UTF_8);
        assertThat(sourceCount()).as("Original simulated Source count").isEqualTo(20);
        String count=System.getenv("KAFKA_LIVE_MESSAGES");simulatedMessages=count==null?1:Integer.parseInt(count);assertThat(simulatedMessages).isBetween(1,20);
        String sourceBefore=sourceFingerprint();
        try {
            broker();
            int fport=port(),pport=port();while(pport==fport)pport=port();foundation="http://127.0.0.1:"+fport;provider="http://127.0.0.1:"+pport;
            var sourceUri=URI.create(env("PROVIDER_SOURCE_DB_URL").substring(5));
            var f=new HashMap<String,String>(kafkaEnv("FOUNDATION_KAFKA_",probe,probePassword));
            f.putAll(Map.of("KAFKA_SUBSCRIPTION_EXECUTION_ENABLED","true","KAFKA_SUBSCRIPTION_CLUSTER_ID",admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS),
                "KAFKA_SUBSCRIPTION_ADMIN_BOOTSTRAP_SERVERS",brokerAddress,"KAFKA_SUBSCRIPTION_ADMIN_SECURITY_PROTOCOL","SASL_PLAINTEXT",
                "KAFKA_SUBSCRIPTION_ADMIN_SASL_MECHANISM",operatorMechanism,"KAFKA_SUBSCRIPTION_ADMIN_USERNAME",operatorUser,"KAFKA_SUBSCRIPTION_ADMIN_PASSWORD",operatorPassword,"KAFKA_SUBSCRIPTION_RECONCILE_MS","1000"));
            f.putAll(Map.of("FOUNDATION_DB_URL",jdbc(),"FOUNDATION_DB_USER",env("FOUNDATION_DB_USER"),"FOUNDATION_DB_PASSWORD",env("FOUNDATION_DB_PASSWORD"),
                "FOUNDATION_CREDENTIAL_KEY",env("FOUNDATION_CREDENTIAL_KEY"),"FOUNDATION_BIND","127.0.0.1","FOUNDATION_PORT",String.valueOf(fport),
                "PROVIDER_CONTROL_TOKEN",control,"PROVIDER_TELEMETRY_NODE_ID","kafka-live-"+run,"PROVIDER_REST_BASE_URL",provider));
            f.putAll(Map.of("PROVIDER_SOURCE_ENV_PREFIX","SOURCE_KAFKA_LIVE","SOURCE_KAFKA_LIVE_HOST",sourceUri.getHost(),"SOURCE_KAFKA_LIVE_PORT",String.valueOf(sourceUri.getPort()),"SOURCE_KAFKA_LIVE_DATABASE","port_source_db"));
            f.put("FOUNDATION_BOOTSTRAP_ADMIN_USERNAME",workspaceName);f.put("FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD",workspacePassword);
            foundationProcess=service("foundation-service","cn.railway.foundation.FoundationApplication",f);waitHealthy(foundationProcess,foundation+"/api/v1/health");workspaceLogin();
            var p=new HashMap<String,String>(kafkaEnv("PROVIDER_KAFKA_",writer,writerPassword));
            p.putAll(Map.of("PROVIDER_SOURCE_DB_URL",env("PROVIDER_SOURCE_DB_URL"),"PROVIDER_SOURCE_DB_USER",env("PROVIDER_SOURCE_DB_USER"),"PROVIDER_SOURCE_DB_PASSWORD",env("PROVIDER_SOURCE_DB_PASSWORD"),
                "PROVIDER_BIND","127.0.0.1","PROVIDER_PORT",String.valueOf(pport),"PROVIDER_FOUNDATION_BASE_URL",foundation,"PROVIDER_CONTROL_TOKEN",control,
                "PROVIDER_KAFKA_AUTH_TYPE","SASL","PROVIDER_TELEMETRY_ENABLED","true","PROVIDER_TELEMETRY_SPOOL_DIR",evidence.resolve("spool").toString()));
            providerProcess=service("provider-service","cn.railway.provider.ProviderApplication",p);waitHealthy(providerProcess,provider+"/api/v1/provider/health");
            provision();var resourceBefore=data("/resources/"+resourceId,"GET",null);var fieldsBefore=data("/resources/"+resourceId+"/fields","GET",null);
            browser();
            var result=json.readTree(Files.readString(evidence.resolve("browser-result.json")));
            assertThat(result.path("published").path("topic").asText()).isEqualTo(topic);
            // Enabling production in Foundation never grants subscription rights.
            try(var c=consumer()){
                c.subscribe(List.of(topic));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean denied=false;
                while(System.nanoTime()<end&&!denied)try{c.poll(Duration.ofMillis(100));}catch(org.apache.kafka.common.errors.TopicAuthorizationException ex){denied=true;}
                assertThat(denied).isTrue();
            }
            // Explicit temporary test ACL; no persisted sharing grant or REST policy is modified.
            ExactSubscriptionAcl.grant(admin,reader,topic,group);
            long bytes=0;int messages=0;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try(var c=consumer()){
                c.subscribe(List.of(topic));
                while(System.nanoTime()<end&&messages==0)for(var message:c.poll(Duration.ofMillis(100))){
                    assertThat(message.partition()).isEqualTo(result.path("published").path("partition").asInt());
                    assertThat(message.offset()).isEqualTo(result.path("published").path("offset").asLong());
                    bytes+=message.value().getBytes(StandardCharsets.UTF_8).length;messages++;
                }
            }
            assertThat(messages).isEqualTo(1);ExactSubscriptionAcl.revoke(admin,reader,topic,group);
            JsonNode observed=null;end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<end){var events=data("/runtime-traces/"+result.path("trace_id").asText(),"GET",null);if(events.size()==1){observed=events.get(0);break;}Thread.sleep(100);}
            assertThat(observed).as("Actual telemetry delivery").isNotNull();assertThat(observed.path("outcome").asText()).isEqualTo("SUCCESS");
            assertThat(observed.path("collection_point").asText()).isEqualTo("KAFKA_PRODUCER");assertThat(observed.path("record_count").asLong()).isEqualTo(1);assertThat(observed.path("byte_count").asLong()).isEqualTo(bytes);
            assertThat(observed.path("resource_id").asText()).isEqualTo(resourceId);assertThat(observed.path("capability_id").asText()).isEqualTo(capabilityId);
            assertThat(observed.path("relationship_id").isNull()).isTrue();assertThat(observed.path("subject_id").isNull()).isTrue();assertThat(observed.path("policy_id").isNull()).isTrue();
            assertThat(observed.path("topic_offset").asLong()).isEqualTo(result.path("published").path("offset").asLong());
            var start=Instant.parse(observed.path("completed_at").asText()).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var metric=data("/metrics/windows?collectionPoint=KAFKA_PRODUCER&from="+start+"&until="+start.plusSeconds(60),"GET",null).get(0);
            assertThat(metric.path("unit").asText()).isEqualTo("MESSAGE");assertThat(metric.path("messages").asLong()).isEqualTo(1);
            assertThat(metric.path("bytes").asLong()).isEqualTo(bytes);assertThat(metric.path("calls").isNull()).isTrue();
            var scoped=data("/metrics/windows?collectionPoint=KAFKA_PRODUCER&resourceId="+resourceId+"&from="+start+"&until="+start.plusSeconds(60),"GET",null).get(0);
            assertThat(scoped.path("messages").asLong()).isEqualTo(1);assertThat(scoped.path("bytes").asLong()).isEqualTo(bytes);
            assertThat(observed.toString()).doesNotContain(control,writerPassword,probePassword,readerPassword,"container_no","event_time");
            assertThat(data("/resources/"+resourceId,"GET",null)).isEqualTo(resourceBefore);assertThat(data("/resources/"+resourceId+"/fields","GET",null)).isEqualTo(fieldsBefore);
            simulateRemaining(result.path("published"));subscriptions();
            assertThat(data("/resources/"+resourceId,"GET",null)).isEqualTo(resourceBefore);assertThat(data("/resources/"+resourceId+"/fields","GET",null)).isEqualTo(fieldsBefore);
            restRegression();ownedProviderConfirmation();
            stop(foundationProcess);
            assertThat(externalRest(HttpClient.newHttpClient()).statusCode()).isEqualTo(503);
            var unavailable=response(provider+"/containers/events/1/publish","POST",null,Map.of("X-Provider-Token",control,"X-Capability-Id",capabilityId));
            assertThat(unavailable.statusCode()).isEqualTo(503);assertThat(json.readTree(unavailable.body()).path("data").isNull()).isTrue();
            var offsets=admin.listOffsets(Map.of(new org.apache.kafka.common.TopicPartition(topic,0),OffsetSpec.latest())).all().get(5,TimeUnit.SECONDS);
            assertThat(offsets.values().iterator().next().offset()).as("Initial messages plus three real provider UI publications; rejected attempts never publish").isEqualTo(simulatedMessages+3);
            assertThat(sourceFingerprint()).as("Source rows unchanged, not only their count").isEqualTo(sourceBefore);
            var verification=new LinkedHashMap<String,Object>();verification.put("passed",true);verification.put("messages",simulatedMessages);verification.put("bytes",simulatedBytes);verification.put("scram",true);verification.put("subscriber_denied_before_fixture_acl",true);verification.put("source_rows",sourceCount());verification.put("source_unchanged",true);verification.put("resource_unchanged",true);verification.put("telemetry_matched",true);verification.put("topic",topic);verification.put("production_offsets",simulatedReceipts);verification.put("subscription_revoked",true);verification.put("rest_regression",true);verification.put("provider_confirmation_flow",true);verification.put("simulated_data",true);verification.put("source_data_location","port_source_db.container_event (project SQL seed)");
            Files.writeString(evidence.resolve("verification.json"),json.writeValueAsString(verification),StandardCharsets.UTF_8);
            verification.put("receiver_messages",simulatedMessages+1);verification.put("receiver_database_failure_recovered",true);verification.put("receiver_browser_verified",true);verification.put("receiver_restarted",true);
            Files.writeString(evidence.resolve("verification.json"),json.writeValueAsString(verification),StandardCharsets.UTF_8);
        } finally {
            // Always stop only the processes created above; preserve development services.
            try{stop(browserProcess);stop(receiverProcess);stop(providerProcess);stop(foundationProcess);}finally{
                cleanupReceiver();
                try{try{cleanupNativeBroker();}finally{cleanup();}assertThat(sourceCount()).isEqualTo(20);assertThat(sourceFingerprint()).isEqualTo(sourceBefore);Files.writeString(evidence.resolve("cleanup.json"),json.writeValueAsString(Map.of("metadata_cleaned",true,"source_unchanged",true,"temporary_services_stopped",true,"windows_topic_retained",nativeIdentity!=null&&System.getProperty("os.name").startsWith("Windows"))),StandardCharsets.UTF_8);}finally{
                    if(admin!=null)admin.close(Duration.ofSeconds(1));
                    if(broker!=null){var temporary=Path.of(broker.nodes().baseDirectory());broker.close();assertThat(temporary).doesNotExist();}
                }
            }
        }
        System.out.println("Kafka complete-chain verification passed; evidence: "+evidence);
    }
}
