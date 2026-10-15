package cn.railway.foundation;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.*;
import cn.railway.foundation.security.AccountService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real sessions and direct HTTP attacks, in independent databases and subjects only. */
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceRoleIntegrationTest extends AuthenticatedApiTest {
    @Autowired TestRestTemplate http;
    @Autowired AccountService accounts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cn.railway.foundation.capability.KafkaCapabilityVerifier producerVerifier;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cn.railway.foundation.capability.ProviderSourceBinding producerBinding;
    final ObjectMapper json=new ObjectMapper();
    final List<String> subjects=new ArrayList<>(),sources=new ArrayList<>(),resources=new ArrayList<>(),users=new ArrayList<>(),credentialIds=new ArrayList<>(),reportIds=new ArrayList<>();
    String providerA,providerB,demandA,demandB,sourceA,sourceB,resourceA,resourceB,capA,kafkaA,legacy;
    Session a,b,d,e;String password;
    static String id(String p){return p+"_"+UUID.randomUUID().toString().replace("-","");}
    @DynamicPropertySource static void sourceConfig(DynamicPropertyRegistry p){
        p.add("SOURCE_ROLE_TEST_HOST",()->sourceUri().getHost());p.add("SOURCE_ROLE_TEST_PORT",()->sourceUri().getPort());p.add("SOURCE_ROLE_TEST_DATABASE",()->sourceUri().getPath().substring(1));p.add("SOURCE_ROLE_TEST_USER",()->System.getenv("TEST_SOURCE_DB_USER"));p.add("SOURCE_ROLE_TEST_PASSWORD",()->System.getenv("TEST_SOURCE_DB_PASSWORD"));
    }
    static URI sourceUri(){return URI.create(System.getenv("TEST_SOURCE_DB_URL").substring(5));}
    @BeforeEach void fixture()throws Exception{
        password=UUID.randomUUID().toString();providerA=subject("PROVIDER","提供方甲");providerB=subject("PROVIDER","提供方乙");demandA=subject("DEMAND","需求方甲");demandB=subject("DEMAND","需求方乙");
        sourceA=source(providerA,"提供方甲测试数据源");sourceB=source(providerB,"提供方乙测试数据源");legacy=source(null,"存量无归属测试数据源");
        resourceA=resource(sourceA,"公开资源甲","公开");resourceB=resource(sourceB,"内部资源乙","内部");capA=cap(resourceA,"REST_API");kafkaA=cap(resourceA,"KAFKA_TOPIC");
        a=user("PROVIDER",providerA);b=user("PROVIDER",providerB);d=user("DEMAND",demandA);e=user("DEMAND",demandB);
    }
    String subject(String role,String name){String id=id("SUB");subjects.add(id);identityDb.update("INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,status,created_at,updated_at) VALUES(?,?,?,?,'独立验收单位','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,id.toUpperCase(java.util.Locale.ROOT),name,role);return id;}
    String source(String owner,String name){String id=id("SRC");sources.add(id);identityDb.update("INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,status,provider_subject_id,created_at,updated_at) VALUES(?,?,'MYSQL','SOURCE_ROLE_TEST','ENABLED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,name,owner);return id;}
    String resource(String source,String name,String security){String id=id("RES"),scan=id("SCAN");resources.add(id);identityDb.update("INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES(?,?,'SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",scan,source);identityDb.update("INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES(?,'role_test','container_event','独立快照',1)",scan);identityDb.update("INSERT INTO metadata_scan_field(task_id,schema_name,table_name,field_name,data_type,column_type,nullable,primary_key,technical_comment,ordinal_position) VALUES(?,'role_test','container_event','event_time','datetime','datetime(3)',true,false,'时间',1)",scan);identityDb.update("INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,business_description,owner_department,security_level,update_mode,status,created_at,updated_at) VALUES(?,?,?,'role_test','container_event',?,'独立登记','测试单位',?,'实时','PUBLISHED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,source,scan,name,security);identityDb.update("INSERT INTO resource_field(resource_id,field_name,data_type,primary_key,ordinal_position,time_field) VALUES(?,'event_time','datetime(3)',false,1,true)",id);return id;}
    String cap(String resource,String type){return cap(resource,type,"能力"+type);}
    String cap(String resource,String type,String name){String id=id("CAP");identityDb.update("INSERT INTO capability(capability_id,resource_id,capability_name,type,http_method,endpoint,topic,protocol,auth_type,status,health_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,resource,name,type,type.equals("REST_API")?"GET":"",type.equals("REST_API")?"/containers/events":"",type.equals("KAFKA_TOPIC")?"port.container.event.v1":"",type.equals("KAFKA_TOPIC")?"KAFKA":"HTTP",type.equals("KAFKA_TOPIC")?"SASL":"NONE");return id;}
    Session user(String role,String subject)throws Exception{String name="role_"+UUID.randomUUID().toString().replace("-","");var u=accounts.create(name,role+"验收用户",role,subject,password);users.add(u.userId());return new Session(name,password);}
    class Session{
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        JsonNode csrf;String username;
        Session(String name,String pass)throws Exception{username=name;csrf=request("GET","/auth/csrf",null,200).path("data");if(name!=null){
            var r=client.send(builder("/auth/login",true).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(name,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(pass,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(200);csrf=request("GET","/auth/csrf",null,200).path("data");}}
        HttpRequest.Builder builder(String path,boolean token){var r=HttpRequest.newBuilder(URI.create(http.getRootUri()+"/api/v1"+path));if(token&&csrf!=null)r.header(csrf.path("header_name").asText(),csrf.path("token").asText());return r;}
        JsonNode request(String method,String path,Object body,int status)throws Exception{var r=builder(path,true).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));var response=client.send(r.build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(method+" "+path).isEqualTo(status);return json.readTree(response.body());}
    }
    @Test void loginCookiesCsrfLogoutAndAnonymousContract()throws Exception{
        var anonymous=new Session(null,null);anonymous.request("GET","/sources",null,401);anonymous.request("POST","/sources",Map.of(),401);
        var response=a.client.send(a.builder("/sources/"+sourceA+"/status",false).header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString("{\"status\":\"DISABLED\"}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(403);
        assertThat(a.cookies.getCookieStore().getCookies()).anyMatch(c->c.getName().equals("JSESSIONID")&&c.isHttpOnly());
        a.request("POST","/auth/logout",null,200);a.request("GET","/sources",null,401);
    }
    @Test void producerContextUsesActualOwnerAndCsrfWithoutWritingGovernance()throws Exception{
        org.mockito.Mockito.doNothing().when(producerVerifier).requireConfigured(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doReturn(new cn.railway.foundation.capability.ProviderSourceBinding.Target("127.0.0.1",13306,"port_source_test","container_event")).when(producerBinding).requireSupported(resourceA);
        String path="/capabilities/"+kafkaA+"/producer-context";var before=identityDb.queryForMap("SELECT * FROM capability WHERE capability_id=?",kafkaA);
        for(String method:List.of("GET","POST")){
            var data=a.request(method,path,null,200).path("data");assertThat(data.path("username").asText()).isEqualTo(a.username);assertThat(data.path("subject_id").asText()).isEqualTo(providerA);assertThat(data.path("runtime").path("enabled").asBoolean()).isTrue();assertThat(data.toString()).doesNotContain("password","credential","container_no");
            b.request(method,path,null,403);d.request(method,path,null,403);user("ADMIN",null).request(method,path,null,403);new Session(null,null).request(method,path,null,401);
        }
        var noCsrf=a.client.send(a.builder(path,false).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertThat(noCsrf.statusCode()).isEqualTo(403);
        identityDb.update("UPDATE capability SET status='DISABLED' WHERE capability_id=?",kafkaA);a.request("GET",path,null,403);identityDb.update("UPDATE capability SET status='ENABLED' WHERE capability_id=?",kafkaA);
        assertThat(identityDb.queryForMap("SELECT * FROM capability WHERE capability_id=?",kafkaA)).isEqualTo(before);
    }
    @Test void administratorCatalogFiltersAllPagesAndNeverReturnsPasswordHashes()throws Exception{
        var admin=user("ADMIN",null);String prefix="catalog_"+UUID.randomUUID().toString().replace("-","");
        String hash=identityDb.queryForObject("SELECT password_hash FROM workspace_account WHERE username=?",String.class,a.username);
        for(int i=0;i<205;i++){
            String uid=id("USR");users.add(uid);
            identityDb.update("INSERT INTO workspace_account(user_id,username,display_name,password_hash,role,subject_id,enabled,created_at,updated_at) VALUES(?,?,?,?,'PROVIDER',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",uid,prefix+"_"+i,i==204?prefix+"%_":prefix,hash,providerA,i%2==0);
        }
        var seen=new HashSet<String>();
        for(int offset=0;offset<205;offset+=20){
            var result=admin.request("GET","/accounts/catalog?keyword="+prefix+"&limit=20&offset="+offset,null,200).path("data");
            assertThat(result.path("total").asInt()).isEqualTo(205);
            assertThat(result.toString()).doesNotContain("password",hash,"{bcrypt}");
            for(var row:result.path("items"))assertThat(seen.add(row.path("user_id").asText())).isTrue();
        }
        assertThat(seen).hasSize(205);
        assertThat(admin.request("GET","/accounts/catalog?keyword="+prefix+"&enabled=true&role=PROVIDER",null,200).path("data").path("total").asInt()).isEqualTo(103);
        assertThat(admin.request("GET","/accounts/catalog?keyword="+prefix+"&enabled=false",null,200).path("data").path("total").asInt()).isEqualTo(102);
        assertThat(admin.request("GET","/accounts/catalog?keyword="+prefix+"&role=DEMAND",null,200).path("data").path("total").asInt()).isZero();
        assertThat(admin.request("GET","/accounts/catalog?keyword="+URLEncoder.encode(prefix+"%_",StandardCharsets.UTF_8),null,200).path("data").path("total").asInt()).isEqualTo(1);
        assertThat(admin.request("GET","/accounts/catalog?keyword="+prefix+"&offset=205",null,200).path("data").path("items")).isEmpty();
        for(String query:List.of("limit=0","limit=201","offset=-1","offset=1000001","role=UNKNOWN","enabled=maybe","keyword="+"x".repeat(121)))admin.request("GET","/accounts/catalog?"+query,null,400);
        for(var ordinary:List.of(a,d))ordinary.request("GET","/accounts/catalog",null,403);
        new Session(null,null).request("GET","/accounts/catalog",null,401);
        assertThat(admin.request("GET","/accounts?limit=1",null,200).path("data").isArray()).isTrue();
    }
    @Test void subjectOptionsArePagedRoleMatchedEnabledAndAdministratorOnly()throws Exception{
        var admin=user("ADMIN",null);String prefix="subjectoptions_"+UUID.randomUUID().toString().replace("-","");
        for(int i=0;i<201;i++)subject("PROVIDER",prefix+String.format("%03d",i));
        String disabled=subject("PROVIDER",prefix+"_disabled");identityDb.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",disabled);
        String other=subject("DEMAND",prefix+"_demand");
        var first=admin.request("GET","/accounts/subject-options?role=PROVIDER&keyword="+prefix,null,200).path("data");
        assertThat(first.path("total").asInt()).isEqualTo(201);assertThat(first.path("items").size()).isEqualTo(20);
        var last=admin.request("GET","/accounts/subject-options?role=PROVIDER&keyword="+prefix+"&offset=200",null,200).path("data");
        assertThat(last.path("items").size()).isEqualTo(1);assertThat(last.path("items").get(0).path("subject_name").asText()).isEqualTo(prefix+"200");
        assertThat(first.toString()).doesNotContain(disabled,other,"credential","password");
        var demand=admin.request("GET","/accounts/subject-options?role=DEMAND&keyword="+prefix,null,200).path("data");assertThat(demand.path("total").asInt()).isEqualTo(1);
        assertThat(demand.path("items").get(0).path("subject_id").asText()).isEqualTo(other);
        for(var ordinary:List.of(a,d))ordinary.request("GET","/accounts/subject-options?role=PROVIDER",null,403);
        new Session(null,null).request("GET","/accounts/subject-options?role=PROVIDER",null,401);
        for(String query:List.of("role=ADMIN","role=PROVIDER&limit=201","role=PROVIDER&offset=-1"))admin.request("GET","/accounts/subject-options?"+query,null,400);
    }
    @Test void administratorAccountWritesValidateUnicodeAndProtectVersions()throws Exception{
        var admin=user("ADMIN",null);String uid=a.request("GET","/auth/me",null,200).path("data").path("user_id").asText();
        var before=identityDb.queryForMap("SELECT password_hash,version FROM workspace_account WHERE user_id=?",uid);
        for(String bad:List.of("short","😀".repeat(6),"汉".repeat(25))){
            admin.request("POST","/accounts/"+uid+"/password",Map.of("version",1,"password",bad),400);
            admin.request("POST","/accounts",Map.of("username","reject_"+UUID.randomUUID().toString().replace("-",""),"display_name","不应登记","role","ADMIN","password",bad),400);
        }
        assertThat(identityDb.queryForMap("SELECT password_hash,version FROM workspace_account WHERE user_id=?",uid)).isEqualTo(before);
        admin.request("POST","/accounts/"+uid+"/status",Map.of("version",1,"enabled",false),200);
        admin.request("POST","/accounts/"+uid+"/password",Map.of("version",1,"password","汉".repeat(24)),409);
        admin.request("POST","/accounts/"+uid+"/status",Map.of("version",1,"enabled",true),409);a.request("GET","/auth/me",null,401);
        admin.request("POST","/accounts/"+uid+"/password",Map.of("version",2,"password","汉".repeat(24)),200);
        admin.request("POST","/accounts/"+uid+"/status",Map.of("version",3,"enabled",true),200);
        new Session(a.username,"汉".repeat(24)).request("GET","/auth/me",null,200);
        assertThat(identityDb.queryForObject("SELECT subject_id FROM workspace_account WHERE user_id=?",String.class,uid)).isEqualTo(providerA);
        String own=admin.request("GET","/auth/me",null,200).path("data").path("user_id").asText();
        admin.request("POST","/accounts/"+own+"/status",Map.of("version",1,"enabled",false),400);
    }
    @Test void allRolesChangeOnlyOwnPasswordAndInvalidateAllOldSessions()throws Exception{
        var administrator=user("ADMIN",null);
        for(var actor:List.of(administrator,a,d)){
            var duplicate=new Session(actor.username,password);
            var before=identityDb.queryForMap("SELECT * FROM workspace_account WHERE username=?",actor.username);
            String next="Changed_"+UUID.randomUUID().toString();
            var result=actor.client.send(actor.builder("/auth/password",true).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("current_password",password,"new_password",next)))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(200);assertThat(json.readTree(result.body()).path("data").isNull()).isTrue();
            assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(result.headers().allValues("Set-Cookie")).anyMatch(v->v.contains("JSESSIONID=")&&v.contains("Max-Age=0"));
            actor.request("GET","/auth/me",null,401);duplicate.request("GET","/auth/me",null,401);
            var anonymous=new Session(null,null);
            var oldLogin=anonymous.client.send(anonymous.builder("/auth/login",true).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+actor.username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(oldLogin.statusCode()).isEqualTo(401);
            var renewed=new Session(actor.username,next);renewed.request("GET","/auth/me",null,200);
            var after=identityDb.queryForMap("SELECT * FROM workspace_account WHERE username=?",actor.username);
            assertThat(after.get("password_hash")).isNotEqualTo(before.get("password_hash"));
            assertThat((Integer)after.get("version")).isEqualTo((Integer)before.get("version")+1);
            for(String column:List.of("user_id","username","display_name","role","subject_id","enabled","created_at"))assertThat(after.get(column)).isEqualTo(before.get(column));
            assertThat(after.get("password_hash").toString()).startsWith("{bcrypt}").doesNotContain(next,password);
        }
    }
    @Test void ownPasswordRejectsBadSecretsCsrfAndForgedTargetWithoutChangingAnyAccount()throws Exception{
        var before=identityDb.queryForList("SELECT user_id,password_hash,version FROM workspace_account ORDER BY user_id");
        var good=Map.of("current_password",password,"new_password","Changed_"+UUID.randomUUID());
        new Session(null,null).request("POST","/auth/password",good,401);
        var noCsrf=a.client.send(a.builder("/auth/password",false).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(good))).build(),HttpResponse.BodyHandlers.ofString());assertThat(noCsrf.statusCode()).isEqualTo(403);
        a.request("POST","/auth/password",Map.of("current_password","wrong","new_password",good.get("new_password")),400);
        a.request("POST","/auth/password",Map.of("current_password",password,"new_password",password),400);
        a.request("POST","/auth/password",Map.of("current_password",password,"new_password","short"),400);
        a.request("POST","/auth/password",Map.of("current_password",password,"new_password","😀".repeat(6)),400);
        a.request("POST","/auth/password",Map.of("current_password",password,"new_password","汉".repeat(25)),400);
        var forged=new HashMap<String,Object>(good);forged.put("user_id",b.request("GET","/auth/me",null,200).path("data").path("user_id").asText());a.request("POST","/auth/password",forged,400);
        assertThat(identityDb.queryForList("SELECT user_id,password_hash,version FROM workspace_account ORDER BY user_id")).isEqualTo(before);
        a.request("GET","/auth/me",null,200);
    }
    @Test void loginPasswordChangeDoesNotModifyExternalCredentialsOrKafkaApplications()throws Exception{
        d.request("POST","/subjects/"+demandA+"/credentials",Map.of("credential_name","独立凭据","expires_at",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","凭据隔离测试"),200);
        d.request("POST","/kafka-subscriptions",application(demandA,true),200);
        var credentials=identityDb.queryForList("SELECT * FROM caller_credential WHERE subject_id=?",demandA);
        var grants=identityDb.queryForList("SELECT * FROM kafka_subscription_grant WHERE demand_subject_id=?",demandA);
        d.request("POST","/auth/password",Map.of("current_password",password,"new_password","Changed_"+UUID.randomUUID()),200);
        assertThat(identityDb.queryForList("SELECT * FROM caller_credential WHERE subject_id=?",demandA)).isEqualTo(credentials);
        assertThat(identityDb.queryForList("SELECT * FROM kafka_subscription_grant WHERE demand_subject_id=?",demandA)).isEqualTo(grants);
    }
    @Test void providerScopePreventsDirectIdsWritesSnapshotsAndUnownedTakeover()throws Exception{
        assertThat(a.request("GET","/sources?limit=1&offset=0",null,200).path("data").get(0).path("source_id").asText()).isEqualTo(sourceA);
        assertThat(a.request("GET","/sources?limit=1&offset=1",null,200).path("data")).isEmpty();
        for(String path:List.of("/sources/"+sourceB,"/sources/"+legacy,"/resources/"+resourceB,"/resources/"+resourceB+"/fields","/resources/"+resourceB+"/versions","/resources/"+resourceB+"/schema-comparison/scans"))a.request("GET",path,null,403);
        a.request("PATCH","/sources/"+sourceB+"/status",Map.of("status","DISABLED"),403);
        a.request("GET","/accounts",null,403);a.request("POST","/subjects",Map.of(),403);
        a.request("POST","/capabilities",Map.of("resource_id",resourceB,"capability_name","越权","type","REST_API"),403);
        a.request("POST","/sources",Map.of("source_name","越权引用","source_type","MYSQL","connection_env_prefix","SOURCE_ROLE_TEST"),403);
        var scanned=a.request("POST","/sources/"+sourceA+"/scan",null,200).path("data");assertThat(scanned.path("status").asText()).isEqualTo("SUCCESS");String scan=scanned.path("task_id").asText();
        b.request("GET","/scan-tasks/"+scan+"/tables",null,403);assertThat(b.request("GET","/scan-tasks",null,200).toString()).doesNotContain(scan);
    }
    Map<String,Object> application(String demand,boolean kafka){var r=new LinkedHashMap<String,Object>();r.put(kafka?"grant_name":"relationship_name","验收申请");r.put("provider_subject_id",providerA);r.put("demand_subject_id",demand);r.put("resource_id",resourceA);r.put("capability_id",kafka?kafkaA:capA);r.put("purpose","独立权限验收");r.put("reason","验收登记");r.put("valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());r.put("valid_until",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());if(kafka)r.put("whole_topic_acknowledged",true);else r.put("policy",Map.of("allowed_fields",List.of("event_time"),"time_field","event_time","data_from","2026-09-20T00:00:00Z","data_until","2026-09-21T00:00:00Z"));return r;}
    @Test void demandCatalogCannotExposeSecretsOrAlterOwnedMetadata()throws Exception{
        var data=d.request("GET","/resources",null,200).path("data");assertThat(data.toString()).contains(resourceA).doesNotContain(resourceB,"password","credential_ref","connection_env_prefix","source_host","username");
        d.request("GET","/resources/"+resourceB,null,403);d.request("GET","/sources",null,403);d.request("GET","/capabilities/"+capA+"/runtime",null,403);d.request("GET","/metrics/windows?collectionPoint=REST_PROVIDER",null,403);
        d.request("PUT","/resources/"+resourceA,Map.of(),403);d.request("POST","/capabilities/"+capA+"/disable",Map.of(),403);d.request("POST","/accounts",Map.of(),403);
        d.request("POST","/relationships",application(demandB,false),403);var wrong=application(demandA,false);wrong.put("provider_subject_id",providerB);d.request("POST","/relationships",wrong,403);
        identityDb.update("UPDATE source_system SET status='DISABLED' WHERE source_id=?",sourceA);d.request("GET","/resources/"+resourceA,null,403);
    }
    @Test void restApplicationsAndCredentialsUseRealIdentityAndOwnScope()throws Exception{
        String rel=d.request("POST","/relationships",application(demandA,false),200).path("data").path("relationship_id").asText();
        e.request("GET","/relationships/"+rel,null,403);e.request("POST","/relationships/"+rel+"/revoke",Map.of("version",1,"reason","越权"),403);
        a.request("GET","/relationships/"+rel,null,200);b.request("GET","/relationships/"+rel,null,403);
        d.request("POST","/relationships/"+rel+"/submit",Map.of("version",1,"reason","提交"),200);
        for(Session ordinary:List.of(a,d))ordinary.request("POST","/relationships/"+rel+"/confirm",Map.of("version",2,"reason","越权审核"),403);
        assertThat(identityDb.queryForObject("SELECT operator_name FROM sharing_relationship_event WHERE relationship_id=? AND action='CREATE'",String.class,rel)).isEqualTo(d.username);
        assertThat(identityDb.queryForObject("SELECT operator_user_id FROM sharing_relationship_event WHERE relationship_id=? AND action='CREATE'",String.class,rel)).isNotNull();
        var issue=Map.of("credential_name","验收凭据","expires_at",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),"reason","领取");
        d.request("POST","/subjects/"+demandB+"/credentials",issue,403);String credential=d.request("POST","/subjects/"+demandA+"/credentials",issue,200).path("data").path("credential").path("credential_id").asText();
        e.request("GET","/caller-credentials/"+credential,null,403);e.request("GET","/caller-credentials/"+credential+"/events",null,403);e.request("POST","/caller-credentials/"+credential+"/revoke",Map.of("version",1,"reason","越权"),403);
        assertThat(e.request("GET","/caller-credentials",null,200).path("data")).isEmpty();assertThat(d.request("GET","/caller-credentials",null,200).toString()).doesNotContain("token_digest","rdf_");
    }
    @Test void kafkaTotalsAndActionsAreScopedAndNeverSelfApproved()throws Exception{
        String grant=d.request("POST","/kafka-subscriptions",application(demandA,true),200).path("data").path("grant_id").asText();e.request("POST","/kafka-subscriptions",application(demandB,true),200);
        assertThat(d.request("GET","/kafka-subscriptions?limit=1&offset=0",null,200).path("data").path("total").asLong()).isEqualTo(1);
        assertThat(b.request("GET","/kafka-subscriptions",null,200).path("data").path("total").asLong()).isZero();
        e.request("GET","/kafka-subscriptions/"+grant,null,403);e.request("POST","/kafka-subscriptions/"+grant+"/credential",Map.of("version",1,"reason","越权"),403);
        for(String action:List.of("confirm","return","provision","retry"))d.request("POST","/kafka-subscriptions/"+grant+"/"+action,Map.of("version",1,"reason","越权","whole_topic_acknowledged",true),403);
        a.request("POST","/kafka-subscriptions/"+grant+"/submit",Map.of("version",1,"reason","越权"),403);
        d.request("POST","/kafka-subscriptions/"+grant+"/submit",Map.of("version",1,"reason","提交"),200);d.request("POST","/kafka-subscriptions/"+grant+"/revoke",Map.of("version",2,"reason","撤回"),200);
    }
    String reviewPath(boolean kafka){return kafka?"/kafka-subscriptions":"/relationships";}
    String reviewId(JsonNode row,boolean kafka){return row.path(kafka?"grant_id":"relationship_id").asText();}
    JsonNode reviewDraft(boolean kafka)throws Exception{return d.request("POST",reviewPath(kafka),application(demandA,kafka),200).path("data");}
    Map<String,Object> reviewBody(int version,boolean acknowledged){return Map.of("version",version,"reason","独立提供方确认验收","content_acknowledged",acknowledged);}
    Map<String,Object> adminReviewBody(int version,boolean kafka){var body=new HashMap<String,Object>();body.put("version",version);body.put("reason","管理员最终审批");if(kafka)body.put("whole_topic_acknowledged",true);return body;}
    @Test void ownedApplicationsRequireProviderAndSeparateAdminReview()throws Exception{
        var admin=user("ADMIN",null);
        for(boolean kafka:List.of(false,true)){
            var draft=reviewDraft(kafka);String path=reviewPath(kafka)+"/"+reviewId(draft,kafka);
            assertThat(draft.path("provider_review_mode").asText()).isEqualTo("REQUIRED");
            a.request("POST",path+"/provider-confirm",reviewBody(1,true),400);
            var pending=d.request("POST",path+"/submit",adminReviewBody(1,kafka),200).path("data");
            for(var denied:List.of(b,d,e,admin))denied.request("POST",path+"/provider-confirm",reviewBody(2,true),403);
            new Session(null,null).request("POST",path+"/provider-confirm",reviewBody(2,true),401);
            admin.request("POST",path+"/confirm",adminReviewBody(2,kafka),400);
            if(kafka){admin.request("POST",path+"/provision",Map.of("version",2,"reason","未审批且当前执行关闭"),503);assertThat(identityDb.queryForObject("SELECT COUNT(*) FROM kafka_subscription_execution WHERE grant_id=?",Integer.class,reviewId(draft,true))).isZero();}
            a.request("POST",path+"/provider-confirm",reviewBody(2,false),400);
            var reviewed=a.request("POST",path+"/provider-confirm",reviewBody(2,true),200).path("data");
            assertThat(reviewed.path("status").asText()).isEqualTo("PENDING");assertThat(reviewed.path("confirmed_at").isNull()).isTrue();
            assertThat(reviewed.path("provider_confirmed_at").isNull()).isFalse();assertThat(reviewed.path("version").asInt()).isEqualTo(3);
            if(!kafka)assertThat(reviewed.path("policy")).isEqualTo(pending.path("policy"));
            a.request("POST",path+"/provider-confirm",reviewBody(3,true),400);a.request("POST",path+"/provider-return",reviewBody(2,false),409);
            var history=a.request("GET",path+"/events",null,200).path("data");assertThat(history.get(2).path("operator_name").asText()).isEqualTo(a.username);assertThat(history.get(2).path("action").asText()).isEqualTo("PROVIDER_CONFIRM");
            var approved=admin.request("POST",path+"/confirm",adminReviewBody(3,kafka),200).path("data");
            assertThat(approved.path("version").asInt()).isEqualTo(4);assertThat(approved.path("provider_confirmed_at")).isEqualTo(reviewed.path("provider_confirmed_at"));
            a.request("POST",path+"/provider-return",reviewBody(4,false),400);
        }
    }
    @Test void providerOrAdminReturnInvalidatesConfirmationBeforeResubmission()throws Exception{
        var admin=user("ADMIN",null);
        for(boolean kafka:List.of(false,true)){
            var draft=reviewDraft(kafka);String path=reviewPath(kafka)+"/"+reviewId(draft,kafka);
            d.request("POST",path+"/submit",adminReviewBody(1,kafka),200);a.request("POST",path+"/provider-confirm",reviewBody(2,true),200);
            var returned=a.request("POST",path+"/provider-return",reviewBody(3,false),200).path("data");
            assertThat(returned.path("status").asText()).isEqualTo("DRAFT");assertThat(returned.path("provider_confirmed_at").isNull()).isTrue();
            var update=new HashMap<>(application(demandA,kafka));for(String key:List.of("provider_subject_id","demand_subject_id","resource_id","capability_id","whole_topic_acknowledged"))update.remove(key);
            update.put("version",4);update.put("purpose","退回后调整用途");d.request("PUT",path,update,200);
            d.request("POST",path+"/submit",adminReviewBody(5,kafka),200);admin.request("POST",path+"/confirm",adminReviewBody(6,kafka),400);
            a.request("POST",path+"/provider-confirm",reviewBody(6,true),200);
            var adminReturn=admin.request("POST",path+"/return",adminReviewBody(7,kafka),200).path("data");assertThat(adminReturn.path("provider_confirmed_at").isNull()).isTrue();
            d.request("POST",path+"/submit",adminReviewBody(8,kafka),200);admin.request("POST",path+"/confirm",adminReviewBody(9,kafka),400);
        }
    }
    @Test void legacyApplicationsAndUnassignedSourcesRetainAdminFlowWithoutInventedProviderHistory()throws Exception{
        var admin=user("ADMIN",null);
        for(boolean kafka:List.of(false,true)){
            var draft=reviewDraft(kafka);String id=reviewId(draft,kafka),path=reviewPath(kafka)+"/"+id,table=kafka?"kafka_subscription_grant":"sharing_relationship",key=kafka?"grant_id":"relationship_id";
            identityDb.update("UPDATE "+table+" SET provider_review_mode='LEGACY' WHERE "+key+"=?",id);
            d.request("POST",path+"/submit",adminReviewBody(1,kafka),200);a.request("POST",path+"/provider-confirm",reviewBody(2,true),400);
            var approved=admin.request("POST",path+"/confirm",adminReviewBody(2,kafka),200).path("data");assertThat(approved.path("provider_review_mode").asText()).isEqualTo("LEGACY");assertThat(approved.path("provider_confirmed_at").isNull()).isTrue();
            assertThat(admin.request("GET",path+"/events",null,200).path("data").toString()).doesNotContain("PROVIDER_CONFIRM");
            String unassignedSource=source(null,"未归属来源"+(kafka?"Kafka":"REST"));
            String resource=resource(unassignedSource,"未归属资源验收"+(kafka?"Kafka":"REST"),"公开"),capability=cap(resource,kafka?"KAFKA_TOPIC":"REST_API");
            var body=new HashMap<>(application(demandA,kafka));body.put("resource_id",resource);body.put("capability_id",capability);
            var unassigned=admin.request("POST",reviewPath(kafka),body,200).path("data");assertThat(unassigned.path("provider_review_mode").asText()).isEqualTo("ADMIN_MANAGED");
            a.request("POST",reviewPath(kafka)+"/"+reviewId(unassigned,kafka)+"/provider-confirm",reviewBody(1,true),403);
            assertThat(identityDb.queryForObject("SELECT provider_subject_id FROM source_system WHERE source_id=?",String.class,legacy)).isNull();
        }
    }
    @Test void providerConfirmationAndHistoryRollBackTogether()throws Exception{
        for(boolean kafka:List.of(false,true)){
            var draft=reviewDraft(kafka);String id=reviewId(draft,kafka),path=reviewPath(kafka)+"/"+id;
            d.request("POST",path+"/submit",adminReviewBody(1,kafka),200);
            if(kafka)identityDb.update("INSERT INTO kafka_subscription_event(grant_id,action,from_status,to_status,whole_topic_acknowledged,operator_name,reason,version,occurred_at) VALUES(?,'TEST','PENDING','PENDING',false,'独立测试','故意制造历史冲突',3,UTC_TIMESTAMP(3))",id);
            else identityDb.update("INSERT INTO sharing_relationship_event(relationship_id,action,from_status,to_status,operator_name,reason,version,occurred_at) VALUES(?,'TEST','PENDING','PENDING','独立测试','故意制造历史冲突',3,UTC_TIMESTAMP(3))",id);
            a.request("POST",path+"/provider-confirm",reviewBody(2,true),500);
            var current=d.request("GET",path,null,200).path("data");assertThat(current.path("version").asInt()).isEqualTo(2);assertThat(current.path("provider_confirmed_at").isNull()).isTrue();
        }
    }
    @Test void concurrentProviderReturnAndAdminApprovalOnlyCommitOneVersion()throws Exception{
        var admin=user("ADMIN",null);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{for(boolean kafka:List.of(false,true)){
            var draft=reviewDraft(kafka);String path=reviewPath(kafka)+"/"+reviewId(draft,kafka);
            d.request("POST",path+"/submit",adminReviewBody(1,kafka),200);a.request("POST",path+"/provider-confirm",reviewBody(2,true),200);
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> confirm=()->{start.await();return admin.client.send(admin.builder(path+"/confirm",true).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(adminReviewBody(3,kafka)))).build(),HttpResponse.BodyHandlers.ofString()).statusCode();};
            java.util.concurrent.Callable<Integer> giveBack=()->{start.await();return a.client.send(a.builder(path+"/provider-return",true).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(reviewBody(3,false)))).build(),HttpResponse.BodyHandlers.ofString()).statusCode();};
            var x=pool.submit(confirm);var y=pool.submit(giveBack);start.countDown();assertThat(List.of(x.get(),y.get())).containsExactlyInAnyOrder(200,409);
            var current=d.request("GET",path,null,200).path("data");assertThat(current.path("version").asInt()).isEqualTo(4);
            assertThat(current.path("status").asText()).isIn(kafka?"APPROVED":"CONFIRMED","DRAFT");
        }}finally{pool.shutdownNow();}
    }
    @Test void consumptionReadScopesAndWorkspacesCannotReplaceReportIdentity()throws Exception{
        String grant=d.request("POST","/kafka-subscriptions",application(demandA,true),200).path("data").path("grant_id").asText();
        for(var allowed:List.of(a,d)){var page=allowed.request("GET","/kafka-subscriptions/"+grant+"/consumption",null,200).path("data");assertThat(page.path("total").asInt()).isZero();assertThat(page.path("summary").path("records").isNull()).isTrue();}
        for(var denied:List.of(b,e))denied.request("GET","/kafka-subscriptions/"+grant+"/consumption?limit=1&offset=0",null,403);
        new Session(null,null).request("GET","/kafka-subscriptions/"+grant+"/consumption",null,401);
        String now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
        d.request("POST","/kafka-subscriptions/"+grant+"/consumption-reports",Map.of("report_id",UUID.randomUUID().toString(),"started_at",now,"completed_at",now,"positions",List.of()),401);
        d.request("GET","/auth/me",null,200);
    }
    @Test void disabledAccountsAndSubjectsInvalidateSessionsAndHashesStayPrivate()throws Exception{
        String uid=identityDb.queryForObject("SELECT user_id FROM workspace_account WHERE username=?",String.class,d.username);
        assertThat(identityDb.queryForObject("SELECT password_hash FROM workspace_account WHERE user_id=?",String.class,uid)).startsWith("{bcrypt}").doesNotContain(password);
        identityDb.update("UPDATE workspace_account SET version=version+1 WHERE user_id=?",uid);d.request("GET","/auth/me",null,401);
        identityDb.update("UPDATE sharing_subject SET status='DISABLED' WHERE subject_id=?",providerA);a.request("GET","/sources",null,401);
        var bad=Map.of("username","invalid_"+UUID.randomUUID().toString().replace("-",""),"display_name","角色错配","role","PROVIDER","subject_id",demandA,"password",password);
        assertThat(http.postForEntity("/api/v1/accounts",bad,JsonNode.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void providerRegistersDynamicSourceAndOwnResourceWithoutTrustingSubmittedOwner()throws Exception{
        var uri=sourceUri();var body=new LinkedHashMap<String,Object>();body.put("source_name","动态归属验收");body.put("source_type","MYSQL");body.put("host",uri.getHost());body.put("port",uri.getPort());body.put("database_name",uri.getPath().substring(1));body.put("username",System.getenv("TEST_SOURCE_DB_USER"));body.put("password",System.getenv("TEST_SOURCE_DB_PASSWORD"));body.put("ssl_mode","DISABLED");body.put("provider_subject_id",providerB);
        a.request("POST","/sources",body,403);body.remove("provider_subject_id");var source=a.request("POST","/sources",body,200).path("data");String sid=source.path("source_id").asText();sources.add(sid);credentialIds.add(source.path("credential_ref").asText());assertThat(source.path("provider_subject_id").asText()).isEqualTo(providerA);assertThat(source.has("password")).isFalse();
        assertThat(a.request("POST","/sources/"+sid+"/test",null,200).path("data").path("connected").asBoolean()).isTrue();
        String task=a.request("POST","/sources/"+sid+"/scan",null,200).path("data").path("task_id").asText();
        var input=Map.of("source_id",sid,"scan_task_id",task,"schema_name",uri.getPath().substring(1),"physical_table","container_event","resource_name","新登记归属验收","business_description","只扫描元数据","owner_department","测试单位","security_level","公开","update_mode","实时");
        var resource=a.request("POST","/resources",input,200).path("data");String rid=resource.path("resource_id").asText();resources.add(rid);assertThat(resource.path("provider_subject_id").asText()).isEqualTo(providerA);
        a.request("POST","/resources/"+rid+"/publish",null,200);a.request("POST","/capabilities",Map.of("resource_id",rid,"capability_name","本人登记的能力","type","REST_API","http_method","GET","endpoint","/containers/events","protocol","HTTP","auth_type","NONE"),200);
        b.request("GET","/resources/"+rid,null,403);assertThat(identityDb.queryForObject("SELECT operator_name FROM resource_revision WHERE resource_id=? AND version=1",String.class,rid)).isEqualTo(a.username);
        assertThat(identityDb.queryForObject("SELECT provider_subject_id FROM source_system WHERE source_id=?",String.class,legacy)).isNull();
    }
    @Test void providerMonitoringCountsDetailsAndTracesDoNotLeakOtherSources()throws Exception{
        String trace=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();String point="KAFKA_PRODUCER";
        for(String resource:List.of(resourceA,resourceB)){
            String report=UUID.randomUUID().toString(),event=resource.equals(resourceA)?trace:other;reportIds.add(report);
            identityDb.update("INSERT INTO sharing_runtime_report(report_id,collector_id,collection_point,window_start,window_until,window_closed,content_digest,accepted_events,duplicate_events,received_at) VALUES(?,'provider-local',?,UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE),false,?,1,0,UTC_TIMESTAMP(3))",report,point,"a".repeat(64));
            identityDb.update("INSERT INTO sharing_runtime_event(event_id,report_id,trace_id,collector_id,collection_point,resource_id,started_at,completed_at,outcome,reason_code,http_status,record_count,byte_count,duration_ms,content_digest,received_at) VALUES(?,?,?,'provider-local',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'DENIED','OPERATOR_FORBIDDEN',403,0,0,1,?,UTC_TIMESTAMP(3))",event,report,event,point,resource,"b".repeat(64));
        }
        var page=a.request("GET","/runtime-events?limit=1&offset=0",null,200).path("data");assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.toString()).contains(trace).doesNotContain(other);
        a.request("GET","/runtime-events/"+other,null,403);a.request("GET","/runtime-traces/"+other,null,403);a.request("GET","/runtime-collector",null,403);
        var window=a.request("GET","/metrics/windows?collectionPoint=KAFKA_PRODUCER",null,200).path("data");for(var row:window){assertThat(row.path("reports").asLong()).isZero();assertThat(row.path("state").asText()).isEqualTo("UNKNOWN");}
    }
    @Test @EnabledIfEnvironmentVariable(named="RUN_ROLE_BROWSER",matches="true") void actualBrowserThreeWorkspaces()throws Exception{
        // A new random test admin for browser only; no password is written into evidence or a repository file.
        Session admin=user("ADMIN",null);
        // Two independent public providers exercise dependent Kafka application choices. No Broker ACL is granted.
        identityDb.update("UPDATE data_resource SET security_level='公开' WHERE resource_id=?",resourceB);
        String kafkaB=cap(resourceB,"KAFKA_TOPIC"),nonSasl=cap(resourceA,"KAFKA_TOPIC","无独立认证主题");
        identityDb.update("UPDATE capability SET auth_type='NONE' WHERE capability_id=?",nonSasl);
        String accountPrefix="accountui_"+UUID.randomUUID().toString().replace("-","");
        String hash=identityDb.queryForObject("SELECT password_hash FROM workspace_account WHERE username=?",String.class,a.username);
        for(int i=0;i<30;i++){String uid=id("USR");users.add(uid);identityDb.update("INSERT INTO workspace_account(user_id,username,display_name,password_hash,role,subject_id,created_at,updated_at) VALUES(?,?,?,?,'PROVIDER',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",uid,accountPrefix+"_"+i,"浏览器分页验收",hash,providerA);}
        String optionPrefix="主体选择_"+UUID.randomUUID().toString().replace("-","");
        for(int i=0;i<21;i++)subject("PROVIDER",optionPrefix+String.format("%02d",i));
        Path web=Path.of("..","foundation-web").toAbsolutePath().normalize();String node=System.getenv("ROLE_NODE_EXECUTABLE");
        var builder=new ProcessBuilder(node==null?"node":node,"scripts/role-workspaces-live.mjs").directory(web.toFile()).redirectErrorStream(true).redirectOutput(web.resolve("../../.runtime/role-browser.log").toFile());
        var env=builder.environment();env.put("ROLE_FOUNDATION_URL",http.getRootUri());env.put("ROLE_PASSWORD",password);env.put("ROLE_ADMIN",admin.username);env.put("ROLE_PROVIDER",a.username);env.put("ROLE_DEMAND",d.username);env.put("ROLE_RESOURCE",resourceA);env.put("ROLE_RESOURCE_B",resourceB);env.put("ROLE_SOURCE",sourceA);env.put("ROLE_KAFKA",kafkaA);env.put("ROLE_KAFKA_B",kafkaB);env.put("ROLE_REST",capA);env.put("ROLE_ACCOUNT_PREFIX",accountPrefix);env.put("ROLE_OPTION_PREFIX",optionPrefix);
        var process=builder.start();try{assertThat(process.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();}finally{process.destroyForcibly();}
    }
    @AfterEach void cleanup(){
        for(String report:reportIds){identityDb.update("DELETE FROM sharing_runtime_event WHERE report_id=?",report);identityDb.update("DELETE FROM sharing_runtime_report WHERE report_id=?",report);}
        for(String user:users)identityDb.update("DELETE FROM workspace_account WHERE user_id=?",user);
        for(String subject:subjects){identityDb.update("DELETE e FROM caller_credential_event e JOIN caller_credential c ON c.credential_id=e.credential_id WHERE c.subject_id=?",subject);identityDb.update("DELETE FROM caller_credential WHERE subject_id=?",subject);}
        for(String resource:resources){
            var policyIds=identityDb.queryForList("SELECT policy_id FROM sharing_relationship WHERE resource_id=?",String.class,resource);
            identityDb.update("DELETE e FROM sharing_relationship_event e JOIN sharing_relationship r ON r.relationship_id=e.relationship_id WHERE r.resource_id=?",resource);identityDb.update("DELETE FROM sharing_relationship WHERE resource_id=?",resource);for(String policy:policyIds)identityDb.update("DELETE FROM access_policy WHERE policy_id=?",policy);
            identityDb.update("DELETE e FROM kafka_subscription_event e JOIN kafka_subscription_grant g ON g.grant_id=e.grant_id WHERE g.resource_id=?",resource);identityDb.update("DELETE FROM kafka_subscription_grant WHERE resource_id=?",resource);
            identityDb.update("DELETE FROM capability WHERE resource_id=?",resource);identityDb.update("DELETE FROM resource_field WHERE resource_id=?",resource);identityDb.update("DELETE FROM data_resource WHERE resource_id=?",resource);
        }
        for(String source:sources){var tasks=identityDb.queryForList("SELECT task_id FROM metadata_scan_task WHERE source_id=?",String.class,source);for(String task:tasks){identityDb.update("DELETE FROM metadata_scan_field WHERE task_id=?",task);identityDb.update("DELETE FROM metadata_scan_table WHERE task_id=?",task);}identityDb.update("DELETE FROM metadata_scan_task WHERE source_id=?",source);identityDb.update("DELETE FROM source_system WHERE source_id=?",source);}
        for(String credential:credentialIds)identityDb.update("DELETE FROM source_credential WHERE credential_ref=?",credential);
        for(String subject:subjects){identityDb.update("DELETE FROM workspace_account WHERE subject_id=?",subject);identityDb.update("DELETE FROM sharing_subject WHERE subject_id=?",subject);}
    }
}
