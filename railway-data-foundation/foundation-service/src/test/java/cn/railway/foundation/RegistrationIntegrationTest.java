package cn.railway.foundation;

import cn.railway.foundation.security.*;
import cn.railway.foundation.api.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"foundation.registration.local-bootstrap-enabled=true"})
class RegistrationIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate db; @Autowired PasswordEncoder encoder;
    @Autowired AccountService accounts; @Autowired RegistrationService registrations;
    final ObjectMapper json=new ObjectMapper(); String prefix,password,subject,otherSubject;
    @BeforeEach void setup(){
        assertThat(db.queryForObject("SELECT COUNT(*) FROM workspace_account",Integer.class)).isZero();
        prefix="regtest_"+UUID.randomUUID().toString().replace("-","");password=UUID.randomUUID().toString();
        subject=subject("PROVIDER");otherSubject=subject("DEMAND");
    }
    String subject(String role){String id="SUB_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,status,created_at,updated_at) VALUES(?,?,?,?,'独立注册验收单位','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,id.toUpperCase(Locale.ROOT),prefix+role,role);return id;}
    @AfterEach void cleanup(){
        if(prefix!=null){db.update("DELETE FROM workspace_registration WHERE username LIKE ?",prefix+"%");db.update("DELETE FROM workspace_account WHERE username LIKE ?",prefix+"%");}
        if(subject!=null)db.update("DELETE FROM sharing_subject WHERE subject_id IN (?,?)",subject,otherSubject);
    }
    class Session {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();JsonNode csrf;
        Session()throws Exception{csrf=call("GET","/auth/csrf",null,200).path("data");}
        JsonNode call(String method,String path,Object body,int expected)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create(http.getRootUri()+"/api/v1"+path)).header("Content-Type","application/json");
            if(csrf!=null)builder.header(csrf.path("header_name").asText(),csrf.path("token").asText());
            var res=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(method+" "+path).isEqualTo(expected);return json.readTree(res.body());
        }
        void login(String username,int expected)throws Exception{
            var res=client.send(HttpRequest.newBuilder(URI.create(http.getRootUri()+"/api/v1/auth/login")).header(csrf.path("header_name").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(expected);csrf=call("GET","/auth/csrf",null,200).path("data");
        }
    }
    Map<String,Object> body(String suffix,String role){return Map.of("username",prefix+suffix,"display_name","注册验收用户","role",role,"organization_name","申请单位","password",password);}
    Session admin()throws Exception{registrations.bootstrap(prefix+"admin","独立管理员",password,"127.0.0.1");var session=new Session();session.login(prefix+"admin",200);return session;}
    @Test void firstAdministratorClosedAfterCreationAndDisabledOutsideLocalSetup()throws Exception{
        var anon=new Session();assertThat(anon.call("GET","/auth/registration-options",null,200).path("data").path("bootstrap_available").asBoolean()).isTrue();
        var created=anon.call("POST","/auth/register-admin",Map.of("username",prefix+"admin","display_name","管理员","password",password),200);
        assertThat(created.toString()).doesNotContain(password,"password_hash");anon.login(prefix+"admin",200);
        assertThat(anon.call("GET","/auth/registration-options",null,200).path("data").path("bootstrap_available").asBoolean()).isFalse();
        new Session().call("POST","/auth/register-admin",Map.of("username",prefix+"another","display_name","管理员","password",password),409);
        var disabled=new RegistrationService(db,accounts,encoder,new MockEnvironment().withProperty("server.address","127.0.0.1"));
        assertThat(disabled.availability("127.0.0.1").bootstrapAvailable()).isFalse();
        assertThatThrownBy(()->disabled.bootstrap(prefix+"other","管理员",password,"127.0.0.1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->registrations.bootstrap(prefix+"remote","管理员",password,"192.0.2.1")).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentFirstRegistrationProducesOnlyOneAdministrator()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){final int n=i;futures.add(pool.submit(()->{gate.await();try{registrations.bootstrap(prefix+"first"+n,"管理员",password,"127.0.0.1");return true;}catch(BusinessException ex){return false;}}));}
            gate.countDown();int success=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);assertThat(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE role='ADMIN'",Integer.class)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void pendingHasNoLoginAndApprovalBindsOnlyAdministratorVerifiedSubject()throws Exception{
        var administrator=admin();var anon=new Session();
        String id=anon.call("POST","/auth/register",body("provider","PROVIDER"),200).path("data").path("registration_id").asText();
        String hash=db.queryForObject("SELECT password_hash FROM workspace_registration WHERE registration_id=?",String.class,id);
        assertThat(hash).startsWith("{bcrypt}");assertThat(encoder.matches(password,hash)).isTrue();anon.login(prefix+"provider",401);
        anon.call("GET","/registrations",null,401);anon.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",true,"subject_id",subject,"reason","核对"),401);
        administrator.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",true,"subject_id",otherSubject,"reason","核对"),400);
        var list=administrator.call("GET","/registrations?limit=1",null,200).path("data");assertThat(list.path("total").asInt()).isEqualTo(1);assertThat(list.toString()).doesNotContain(hash,password,"password_hash");
        administrator.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",true,"subject_id",subject,"reason","身份已核实"),200);
        assertThat(db.queryForObject("SELECT password_hash FROM workspace_registration WHERE registration_id=?",String.class,id)).isNull();
        var ordinary=new Session();ordinary.login(prefix+"provider",200);var me=ordinary.call("GET","/auth/me",null,200).path("data");assertThat(me.path("role").asText()).isEqualTo("PROVIDER");assertThat(me.path("subject_id").asText()).isEqualTo(subject);
        ordinary.call("GET","/registrations",null,403);ordinary.call("POST","/registrations/"+id+"/review",Map.of("version",2,"approve",false,"reason","拒绝"),403);
        administrator.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",true,"subject_id",subject,"reason","重复"),409);
        assertThat(db.queryForObject("SELECT reviewed_by FROM workspace_registration WHERE registration_id=?",String.class,id)).isEqualTo(db.queryForObject("SELECT user_id FROM workspace_account WHERE username=?",String.class,prefix+"admin"));
    }
    @Test void invalidRolesCsrfDuplicatesAndRejectionNeverGrantAccess()throws Exception{
        var administrator=admin();var anon=new Session();
        anon.call("POST","/auth/register",body("forged","ADMIN"),400);
        var forged=new HashMap<>(body("forged","DEMAND"));forged.put("subject_id",subject);anon.call("POST","/auth/register",forged,400);
        var weak=new HashMap<>(body("weak","DEMAND"));weak.put("password","short");anon.call("POST","/auth/register",weak,400);
        JsonNode token=anon.csrf;anon.csrf=null;anon.call("POST","/auth/register",body("nocsrf","DEMAND"),401);anon.csrf=token;
        String id=anon.call("POST","/auth/register",body("demand","DEMAND"),200).path("data").path("registration_id").asText();
        anon.call("POST","/auth/register",body("demand","DEMAND"),400);
        administrator.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",false,"reason",""),400);
        administrator.call("POST","/registrations/"+id+"/review",Map.of("version",1,"approve",false,"reason","单位身份未核实"),200);
        assertThat(administrator.call("GET","/registrations?status=REJECTED",null,200).path("data").path("total").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT password_hash FROM workspace_registration WHERE registration_id=?",String.class,id)).isNull();anon.login(prefix+"demand",401);
        for(String query:List.of("limit=0","status=ADMIN","offset=-1"))administrator.call("GET","/registrations?"+query,null,400);
    }
    @Test @EnabledIfEnvironmentVariable(named="RUN_REGISTRATION_BROWSER",matches="true")
    void actualRegistrationBrowser()throws Exception{
        var process=new ProcessBuilder(System.getenv("ROLE_NODE_EXECUTABLE"),"scripts/registration-live.mjs").directory(Path.of("../foundation-web").toFile()).redirectErrorStream(true).redirectOutput(Path.of("../../.runtime/registration-browser.log").toFile());
        process.environment().put("REG_FOUNDATION_URL",http.getRootUri());process.environment().put("REG_PREFIX",prefix);process.environment().put("REG_PASSWORD",password);process.environment().put("REG_SUBJECT_NAME",prefix+"PROVIDER");
        var child=process.start();try{assertThat(child.waitFor(150,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}finally{child.destroyForcibly();}
    }
}
