package cn.railway.foundation.security;

import jakarta.servlet.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

/** Deny unknown endpoints. Scope checks apply to details, writes and nested records, not just menus. */
@Component
public class WorkspaceGate implements HandlerInterceptor, WebMvcConfigurer {
    private final JdbcTemplate db;
    public WorkspaceGate(JdbcTemplate db){this.db=db;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this);}
    public void source(String id){check("SELECT COUNT(*) FROM source_system s WHERE "+Actor.source("s")+" AND s.source_id=?",id);}
    public void resource(String id){check("SELECT COUNT(*) FROM data_resource r WHERE "+Actor.resource("r")+" AND r.resource_id=?",id);}
    public void capability(String id){check("SELECT COUNT(*) FROM capability c JOIN data_resource r ON r.resource_id=c.resource_id WHERE "+Actor.capability("c","r")+" AND c.capability_id=?",id);}
    public void scan(String id){check("SELECT COUNT(*) FROM metadata_scan_task t JOIN source_system s ON s.source_id=t.source_id WHERE "+Actor.source("s")+" AND t.task_id=?",id);}
    public void application(String table,String column,String id){check("SELECT COUNT(*) FROM "+table+" r WHERE "+Actor.sharing("r")+" AND r."+column+"=?",id);}
    private void check(String sql,String id){if(!Actor.admin() && db.queryForObject(sql,Integer.class,Actor.args(id))<1)Actor.deny();}
    public void newApplication(String provider,String demand,String resource,String capability){
        if(!Actor.admin() && (!Actor.demand() || !Objects.equals(demand,Actor.subject())))Actor.deny();
        resource(resource);capability(capability);
        var owners=db.query("SELECT s.provider_subject_id FROM data_resource r JOIN source_system s ON s.source_id=r.source_id WHERE r.resource_id=?",(r,n)->r.getString(1),resource);
        if(!owners.isEmpty() && (owners.get(0)!=null?!Objects.equals(provider,owners.get(0)):!Actor.admin()))Actor.deny();
    }
    public void providerReview(String provider,String resource){
        if(!Actor.provider()||!Objects.equals(provider,Actor.subject()))Actor.deny();
        check("SELECT COUNT(*) FROM data_resource r JOIN source_system s ON s.source_id=r.source_id WHERE s.provider_subject_id=? AND r.resource_id=?",resource);
    }
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        String path=req.getRequestURI(),method=req.getMethod();boolean read=method.equals("GET");
        if(path.equals("/api/v1/health")||Set.of("/api/v1/auth/me","/api/v1/auth/csrf").contains(path)&&read)return true;
        if(read&&path.equals("/api/v1/auth/registration-options")||method.equals("POST")&&Set.of("/api/v1/auth/register","/api/v1/auth/register-admin").contains(path))return true;
        if(SecurityConfiguration.technical(req))return true; // Each technical controller authenticates its own token.
        if(Actor.user()==null)Actor.deny();
        if(path.equals("/api/v1/auth/password")&&method.equals("POST"))return true;
        if(path.equals("/api/v1/auth/receiver-session")&&method.equals("POST")){if(!Actor.demand())Actor.deny();return true;}
        if(path.matches("/api/v1/capabilities/CAP_[a-f0-9]{32}/producer-context")&&(read||method.equals("POST"))){
            if(!Actor.provider())Actor.deny();capability(path.split("/")[4]);return true;
        }
        if(method.equals("POST")&&path.matches("/api/v1/kafka-subscriptions/KSG_[a-f0-9]{32}/receiver-context")){
            if(!Actor.demand())Actor.deny();
            application("kafka_subscription_grant","grant_id",path.split("/")[4]);return true;
        }
        // A platform administrator cannot impersonate the provider, even though other admin routes are unrestricted.
        if(method.equals("POST")&&path.matches("/api/v1/(relationships|kafka-subscriptions)/[^/]+/provider-(confirm|return)")){
            if(!Actor.provider())Actor.deny();
            String[] review=path.substring(8).split("/");
            application(review[0].equals("relationships")?"sharing_relationship":"kafka_subscription_grant",review[0].equals("relationships")?"relationship_id":"grant_id",review[1]);
            return true;
        }
        if(Actor.admin())return true;
        if(!path.startsWith("/api/v1/"))Actor.deny();
        String[] p=path.substring(8).split("/");String group=p[0],id=p.length>1?p[1]:null,action=p.length>2?p[2]:"";
        boolean allowed=false;
        switch(group){
            case "sources" -> {if(Actor.provider()){if(id!=null)source(id);allowed=read&&p.length<=2||p.length==1&&method.equals("POST")||p.length==2&&Set.of("PUT","DELETE").contains(method)||p.length==3&&(Set.of("scan","test").contains(action)&&method.equals("POST")||action.equals("status")&&method.equals("PATCH"));}}
            case "scan-tasks" -> {if(Actor.provider()){if(id!=null)scan(id);allowed=read&&(p.length<=2||p.length==3&&action.equals("tables")||p.length==5&&action.equals("tables")&&p[4].equals("fields"))||method.equals("DELETE")&&p.length==2;}}
            case "resources" -> {
                if(id!=null)resource(id);
                allowed=read&&(p.length<=2||p.length==3&&Set.of("fields","capabilities").contains(action));
                if(Actor.provider())allowed=allowed||p.length==1&&method.equals("POST")||p.length==2&&Set.of("PUT","DELETE").contains(method)||p.length==3&&Set.of("publish","unpublish","republish","retire").contains(action)&&method.equals("POST")||p.length==4&&action.equals("fields")&&method.equals("PUT")||read&&(action.equals("lifecycle-impact")&&p.length==3||action.equals("versions")&&(p.length==3||p.length==4)||action.equals("schema-comparison")&&(p.length==3||p.length==4&&p[3].equals("scans")));
                String scan=req.getParameter("scanTaskId");if(scan!=null)scan(scan);
            }
            case "capabilities" -> {if(id!=null)capability(id);allowed=read&&(p.length<=2||p.length==3&&action.equals("invocation"));if(Actor.provider())allowed=allowed||p.length==1&&method.equals("POST")||p.length==2&&Set.of("PUT","DELETE").contains(method)||p.length==3&&(read&&Set.of("runtime","kafka-runtime").contains(action)||method.equals("POST")&&Set.of("verify","enable","disable").contains(action));}
            case "subjects" -> {
                if(id!=null)check("SELECT COUNT(*) FROM sharing_subject s WHERE "+Actor.subjectOptions("s")+" AND s.subject_id=?",id);
                allowed=read&&p.length<=2||Actor.demand()&&method.equals("POST")&&p.length==3&&action.equals("credentials")&&Objects.equals(id,Actor.subject());
            }
            case "relationships","kafka-subscriptions" -> {
                if(id!=null)application(group.equals("relationships")?"sharing_relationship":"kafka_subscription_grant",group.equals("relationships")?"relationship_id":"grant_id",id);
                allowed=read&&(p.length<=2||p.length==3&&(action.equals("events")||group.equals("kafka-subscriptions")&&action.equals("consumption")))||Actor.demand()&&(p.length==1&&method.equals("POST")||p.length==2&&method.equals("PUT")||p.length==3&&method.equals("POST")&&Set.of("submit","revoke","stop","credential").contains(action));
                if(group.equals("relationships")&&Set.of("stop","credential").contains(action))allowed=false;
            }
            case "access-policies" -> {
                if(id!=null)check("SELECT COUNT(*) FROM sharing_relationship r WHERE "+Actor.sharing("r")+" AND r.policy_id=?",id);
                allowed=read&&p.length<=2;
            }
            case "caller-credentials" -> {
                if(Actor.demand()){
                    if(id!=null)check("SELECT COUNT(*) FROM caller_credential r WHERE r.subject_id=? AND r.credential_id=?",id);
                    if(req.getParameter("subjectId")!=null&&!Actor.subject().equals(req.getParameter("subjectId")))Actor.deny();
                    allowed=read&&(p.length<=2||p.length==3&&action.equals("events"))||method.equals("POST")&&p.length==3&&action.equals("revoke");
                }
            }
            case "runtime-events","runtime-traces","metrics" -> {
                if(Actor.provider()){
                    allowed=read&&(group.equals("metrics")?p.length==2&&id.equals("windows"):p.length<=2);
                    if(group.equals("runtime-events")&&id!=null)check("SELECT COUNT(*) FROM sharing_runtime_event WHERE "+Actor.telemetry()+" AND event_id=?",id);
                    if(group.equals("runtime-traces")&&id!=null)check("SELECT COUNT(*) FROM sharing_runtime_event WHERE "+Actor.telemetry()+" AND trace_id=?",id);
                    if(req.getParameter("resourceId")!=null)resource(req.getParameter("resourceId"));
                    if(req.getParameter("capabilityId")!=null)capability(req.getParameter("capabilityId"));
                    if(req.getParameter("relationshipId")!=null)application("sharing_relationship","relationship_id",req.getParameter("relationshipId"));
                }
            }
            default -> {}
        }
        if(!allowed)Actor.deny();return true;
    }
}
