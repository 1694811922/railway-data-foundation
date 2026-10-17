package cn.railway.foundation.resource;
import cn.railway.foundation.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
/** Resource is the concurrency barrier before a capability, relationship or grant lock. */
public final class ResourceLocks {
    private ResourceLocks(){}
    public static String lock(JdbcTemplate db,String resource){
        var rows=db.query("SELECT status FROM data_resource WHERE resource_id=? FOR UPDATE",(r,n)->r.getString(1),resource);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"资源不存在");return rows.get(0);
    }
    public static String child(JdbcTemplate db,String table,String id){
        String key=Map.of("capability","capability_id","sharing_relationship","relationship_id","kafka_subscription_grant","grant_id").get(table);
        if(key==null)throw new IllegalArgumentException("Unknown resource child");
        var rows=db.query("SELECT resource_id FROM "+table+" WHERE "+key+"=?",(r,n)->r.getString(1),id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"关联记录不存在");
        lock(db,rows.get(0));return rows.get(0);
    }
}
