package cn.railway.foundation.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Null identity is for non-HTTP system jobs; HTTP access is enforced by security chains and the gate. */
public final class Actor {
    private Actor(){}
    public static WorkspaceUser user(){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getPrincipal() instanceof WorkspaceUser u?u:null;}
    public static boolean admin(){return user()==null||"ADMIN".equals(user().role);}
    public static boolean demand(){return user()!=null&&"DEMAND".equals(user().role);}
    public static boolean provider(){return user()!=null&&"PROVIDER".equals(user().role);}
    public static String subject(){return user()==null?null:user().subjectId;}
    public static String operator(){return user()==null?"系统协调器":user().username;}
    public static String userId(){return user()==null?null:user().id;}
    public static void requireAdmin(){if(user()==null||!admin())deny();}
    public static void deny(){throw new AccessDeniedException("无权访问该操作或其他主体的数据");}
    public static Object[] args(Object...tail){var a=new ArrayList<Object>();if(!admin())a.add(subject());a.addAll(Arrays.asList(tail));return a.toArray();}
    public static String source(String alias){return admin()?"1=1":alias+".provider_subject_id=?";}
    public static String resource(String alias){
        if(admin())return "1=1";
        if(provider())return "EXISTS(SELECT 1 FROM source_system own WHERE own.source_id="+alias+".source_id AND own.provider_subject_id=?)";
        // One placeholder keeps every scoped query's args consistent; no frontend identity participates.
        return "? IS NOT NULL AND "+alias+".status='PUBLISHED' AND "+alias+".security_level='公开' AND EXISTS(SELECT 1 FROM source_system own WHERE own.source_id="+alias+".source_id AND own.status='ENABLED')";
    }
    public static String capability(String c,String r){return resource(r)+(demand()?" AND "+c+".status='ENABLED' AND "+c+".health_status='UP'":"");}
    public static String sharing(String alias){return admin()?"1=1":alias+(provider()?".provider_subject_id=?":".demand_subject_id=?");}
    public static String subjectOptions(String alias){
        if(admin())return "1=1";
        if(provider())return alias+".subject_id=?";
        return "("+alias+".subject_id=? OR ("+alias+".subject_type='PROVIDER' AND "+alias+".status='ENABLED' AND EXISTS(SELECT 1 FROM source_system own JOIN data_resource pub ON pub.source_id=own.source_id WHERE own.provider_subject_id="+alias+".subject_id AND own.status='ENABLED' AND pub.status='PUBLISHED' AND pub.security_level='公开')))";
    }
    public static String telemetry(){return admin()?"1=1":"EXISTS(SELECT 1 FROM data_resource ownr JOIN source_system owns ON owns.source_id=ownr.source_id WHERE ownr.resource_id=sharing_runtime_event.resource_id AND owns.provider_subject_id=?)";}
}
