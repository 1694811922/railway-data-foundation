package cn.railway.foundation;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-database-only random administrator. No shared password or production identity. */
public abstract class AuthenticatedApiTest {
    @Autowired TestRestTemplate authenticatedHttp;
    @Autowired JdbcTemplate identityDb;
    @Autowired PasswordEncoder identityEncoder;
    String testAdminId,testAdminUsername,testAdminPassword;
    @BeforeEach void authenticatedAdministrator() throws Exception {
        testAdminId="USR_"+UUID.randomUUID().toString().replace("-","");
        testAdminUsername="test_"+UUID.randomUUID().toString().replace("-","");String password=UUID.randomUUID().toString();testAdminPassword=password;
        identityDb.update("INSERT INTO workspace_account(user_id,username,display_name,password_hash,role,created_at,updated_at) VALUES(?,?,?,?,'ADMIN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",testAdminId,testAdminUsername,"独立回归管理员",identityEncoder.encode(password));
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();var mapper=new ObjectMapper();
        String base=authenticatedHttp.getRootUri();
        var csrf=mapper.readTree(client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("data");
        var login=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/login")).header(csrf.path("header_name").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+testAdminUsername+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        if(login.statusCode()!=200)throw new IllegalStateException("独立测试账号登录失败 HTTP "+login.statusCode());
        var token=mapper.readTree(client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("data");
        String cookie=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).map(c->c.getName()+"="+c.getValue()).findFirst().orElseThrow();
        authenticatedHttp.getRestTemplate().setInterceptors(List.of((request,body,execution)->{request.getHeaders().set("Cookie",cookie);request.getHeaders().set(token.path("header_name").asText(),token.path("token").asText());return execution.execute(request,body);}));
    }
    @AfterEach void removeTestAdministrator(){
        authenticatedHttp.getRestTemplate().setInterceptors(List.of());
        if(testAdminId!=null)identityDb.update("DELETE FROM workspace_account WHERE user_id=?",testAdminId);
    }
}
