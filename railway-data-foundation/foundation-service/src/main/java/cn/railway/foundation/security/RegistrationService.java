package cn.railway.foundation.security;

import cn.railway.foundation.api.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RegistrationService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String registrationId,String username,String displayName,String requestedRole,String organizationName,
                       String status,String subjectId,String reviewReason,int version,LocalDateTime createdAt,LocalDateTime reviewedAt){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Availability(boolean bootstrapAvailable){}
    private final JdbcTemplate db; private final AccountService accounts; private final PasswordEncoder encoder; private final Environment env;
    public RegistrationService(JdbcTemplate db,AccountService accounts,PasswordEncoder encoder,Environment env){this.db=db;this.accounts=accounts;this.encoder=encoder;this.env=env;}
    private static boolean loopback(String address){return Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1","localhost").contains(address==null?"":address);}
    private boolean localBootstrap(String remote){
        // Never trust forwarded headers. A reverse proxy must disable this local setup switch.
        return env.getProperty("foundation.registration.local-bootstrap-enabled",Boolean.class,false)
            && loopback(env.getProperty("server.address","127.0.0.1")) && loopback(remote);
    }
    public Availability availability(String remote){return new Availability(localBootstrap(remote)&&db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE role='ADMIN'",Integer.class)==0);}
    static void identity(String username,String name){
        if(username==null||!username.matches("[A-Za-z][A-Za-z0-9_.-]{2,63}")||name==null||name.isBlank()||name.length()>120)
            invalid("登录账号或用户名称格式不正确");
    }
    @Transactional public AccountService.View bootstrap(String username,String name,String password,String remote){
        if(!localBootstrap(remote))throw new BusinessException(ErrorCode.ACCESS_FORBIDDEN,"首次管理员注册仅限已启用初始化的本机环境");
        accounts.lockRegistration();
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE role='ADMIN'",Integer.class)>0)
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,"管理员已存在，请登录或提交普通注册申请");
        identity(username,name);return accounts.create(username,name,"ADMIN",null,password);
    }
    @Transactional public Map<String,String> submit(String username,String name,String role,String organization,String password){
        identity(username,name);AccountService.validatePassword(password);
        if(!Set.of("PROVIDER","DEMAND").contains(role==null?"":role)||organization==null||organization.isBlank()||organization.length()>120)invalid("请选择提供方或需求方，并填写120字以内的单位名称");
        accounts.lockRegistration();
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE username=?",Integer.class,username)>0
            ||db.queryForObject("SELECT COUNT(*) FROM workspace_registration WHERE username=?",Integer.class,username)>0)invalid("该登录账号不可注册，请更换账号或联系管理员");
        String id="REG_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO workspace_registration(registration_id,username,display_name,requested_role,organization_name,password_hash,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",id,username,name.trim(),role,organization.trim(),encoder.encode(password));
        return Map.of("registration_id",id,"status","PENDING");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AccountService.Page<View> list(String status,int limit,int offset){
        Actor.requireAdmin();
        if(!Set.of("PENDING","APPROVED","REJECTED").contains(status)||limit<1||limit>200||offset<0||offset>1000000)invalid("注册查询参数不正确");
        long total=db.queryForObject("SELECT COUNT(*) FROM workspace_registration WHERE status=?",Long.class,status);
        var rows=db.query("SELECT registration_id,username,display_name,requested_role,organization_name,status,subject_id,review_reason,version,created_at,reviewed_at FROM workspace_registration WHERE status=? ORDER BY created_at DESC,registration_id DESC LIMIT ? OFFSET ?",
            (r,n)->new View(r.getString("registration_id"),r.getString("username"),r.getString("display_name"),r.getString("requested_role"),r.getString("organization_name"),r.getString("status"),r.getString("subject_id"),r.getString("review_reason"),r.getInt("version"),r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toLocalDateTime()),status,limit,offset);
        return new AccountService.Page<>(rows,total);
    }
    @Transactional public void review(String id,int version,boolean approve,String subject,String reason){
        Actor.requireAdmin();accounts.lockRegistration();
        var rows=db.queryForList("SELECT * FROM workspace_registration WHERE registration_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"注册申请不存在");
        var row=rows.get(0);
        if(((Number)row.get("version")).intValue()!=version||!"PENDING".equals(row.get("status")))throw new BusinessException(ErrorCode.VERSION_CONFLICT,"注册申请已处理，请刷新");
        if(reason==null||reason.length()>500||(!approve&&reason.isBlank()))invalid("拒绝时请填写审核意见，最多500字");
        if(approve){
            // Hold subject row so concurrent disabling cannot silently complete a pending approval.
            if(subject==null||db.queryForList("SELECT subject_id FROM sharing_subject WHERE subject_id=? AND subject_type=? AND status='ENABLED' FOR UPDATE",subject,row.get("requested_role")).isEmpty())invalid("请选择启用且角色匹配的共享主体");
            if(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE username=?",Integer.class,row.get("username"))>0)invalid("账号已存在，请核对后拒绝申请");
            db.update("INSERT INTO workspace_account(user_id,username,display_name,password_hash,role,subject_id,created_at,updated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                "USR_"+UUID.randomUUID().toString().replace("-",""),row.get("username"),row.get("display_name"),row.get("password_hash"),row.get("requested_role"),subject);
        }else if(subject!=null)invalid("拒绝申请不能绑定主体");
        db.update("UPDATE workspace_registration SET status=?,subject_id=?,password_hash=NULL,reviewed_by=?,review_reason=?,version=version+1,reviewed_at=UTC_TIMESTAMP(3) WHERE registration_id=?",
            approve?"APPROVED":"REJECTED",approve?subject:null,Actor.userId(),reason.trim(),id);
    }
    private static void invalid(String message){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,message);}
}
