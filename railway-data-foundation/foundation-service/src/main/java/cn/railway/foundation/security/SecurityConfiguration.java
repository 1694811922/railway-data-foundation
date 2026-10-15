package cn.railway.foundation.security;

import cn.railway.foundation.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder(){return new DelegatingPasswordEncoder("bcrypt",Map.of("bcrypt",new BCryptPasswordEncoder(12)));}
    static void json(HttpServletResponse response,ObjectMapper mapper,int status,Object body)throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");mapper.writeValue(response.getWriter(),body);}
    static boolean technical(HttpServletRequest r){
        String p=r.getRequestURI();return p.equals("/api/v1/access/check")||p.equals("/api/v1/telemetry/reports")
            ||("POST".equals(r.getMethod())&&p.matches("/api/v1/kafka-subscriptions/KSG_[a-f0-9]{32}/receiver-access/check"))
            ||("POST".equals(r.getMethod())&&p.matches("/api/v1/kafka-subscriptions/KSG_[a-f0-9]{32}/consumption-reports"))
            ||("GET".equals(r.getMethod())&&p.matches("/api/v1/capabilities/[^/]+/(runtime|kafka-runtime)")&&r.getHeader("X-Provider-Token")!=null)
            ||("GET".equals(r.getMethod())&&p.matches("/api/v1/capabilities/[^/]+/invocation")&&r.getHeader("Authorization")!=null);
    }
    @Bean @Order(1) SecurityFilterChain technicalChain(HttpSecurity h)throws Exception{
        return h.securityMatcher(SecurityConfiguration::technical).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).securityContext(s->s.disable()).requestCache(c->c.disable()).csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
    @Bean @Order(2) SecurityFilterChain workspaceChain(HttpSecurity h,AccountService accounts,ObjectMapper mapper)throws Exception{
        h.authorizeHttpRequests(a->a.requestMatchers("/api/v1/health","/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/auth/registration-options","/api/v1/auth/register","/api/v1/auth/register-admin","/error").permitAll().anyRequest().authenticated())
            .requestCache(c->c.disable()).formLogin(f->f.loginProcessingUrl("/api/v1/auth/login")
                .successHandler((req,res,auth)->json(res,mapper,200,ApiResponse.success(accounts.view((WorkspaceUser)auth.getPrincipal()))))
                .failureHandler((req,res,e)->json(res,mapper,401,new ApiResponse<>(10101,"账号或密码错误，或账号不可用",null))))
            .logout(l->l.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true).deleteCookies("JSESSIONID").logoutSuccessHandler((req,res,a)->json(res,mapper,200,ApiResponse.success(null))))
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->json(res,mapper,401,new ApiResponse<>(10101,"请登录后操作",null)))
                .accessDeniedHandler((req,res,x)->json(res,mapper,Actor.user()==null?401:403,new ApiResponse<>(Actor.user()==null?10101:10102,Actor.user()==null?"请登录后操作":"无权限或请求校验失效，请刷新后重试",null))))
            .sessionManagement(s->s.sessionFixation(f->f.changeSessionId()));
        h.addFilterBefore(new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,jakarta.servlet.FilterChain chain)throws jakarta.servlet.ServletException,IOException{
                if(req.getRequestURI().equals("/api/v1/auth/login")&&req.getMethod().equals("POST")){
                    String password=req.getParameter("password"),name=req.getParameter("username");
                    if(password!=null&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||name!=null&&name.length()>64){json(res,mapper,401,new ApiResponse<>(10101,"账号或密码错误，或账号不可用",null));return;}
                }
                var user=Actor.user();
                if(user!=null){
                    boolean valid=false;
                    try{var current=accounts.loadUserByUsername(user.username);valid=current.id.equals(user.id)&&current.version==user.version&&current.role.equals(user.role)&&java.util.Objects.equals(current.subjectId,user.subjectId);}
                    catch(org.springframework.security.core.userdetails.UsernameNotFoundException ignored){}
                    if(!valid){org.springframework.security.core.context.SecurityContextHolder.clearContext();var session=req.getSession(false);if(session!=null)session.invalidate();json(res,mapper,401,new ApiResponse<>(10101,"登录已失效，请重新登录",null));return;}
                }
                chain.doFilter(req,res);
            }
        },UsernamePasswordAuthenticationFilter.class);
        return h.build();
    }
}
