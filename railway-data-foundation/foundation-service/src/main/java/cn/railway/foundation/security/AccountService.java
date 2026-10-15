package cn.railway.foundation.security;

import cn.railway.foundation.api.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

@Service
public class AccountService implements UserDetailsService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String userId,String username,String displayName,String role,String subjectId,String subjectName,String department,int version,boolean enabled){}
    public record Page<T>(List<T> items,long total){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SubjectOption(String subjectId,String subjectName,String department){}
    private final JdbcTemplate db;private final PasswordEncoder encoder;
    public AccountService(JdbcTemplate db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}
    private static final String SELECT="SELECT a.*,s.subject_type,s.status subject_status,s.subject_name,s.owner_department FROM workspace_account a LEFT JOIN sharing_subject s ON s.subject_id=a.subject_id ";
    private static final String PUBLIC_COLUMNS="SELECT a.user_id,a.username,a.display_name,a.role,a.subject_id,a.version,a.enabled,s.subject_name,s.owner_department ";
    private static final String JOIN="FROM workspace_account a LEFT JOIN sharing_subject s ON s.subject_id=a.subject_id ";
    private static final org.springframework.jdbc.core.RowMapper<View> VIEW=(r,n)->new View(r.getString("user_id"),r.getString("username"),r.getString("display_name"),r.getString("role"),r.getString("subject_id"),r.getString("subject_name"),r.getString("owner_department"),r.getInt("version"),r.getBoolean("enabled"));
    public WorkspaceUser loadUserByUsername(String username){
        var rows=db.query(SELECT+"WHERE a.username=?",(r,n)->{
            if(!r.getBoolean("enabled")||(!"ADMIN".equals(r.getString("role"))&&(!r.getString("role").equals(r.getString("subject_type"))||!"ENABLED".equals(r.getString("subject_status")))))return null;
            return new WorkspaceUser(r.getString("user_id"),r.getString("username"),r.getString("display_name"),r.getString("role"),r.getString("subject_id"),r.getString("subject_name"),r.getString("owner_department"),r.getInt("version"),r.getString("password_hash"));
        },username);
        if(rows.isEmpty()||rows.get(0)==null)throw new UsernameNotFoundException("账号不可用");return rows.get(0);
    }
    public View view(WorkspaceUser u){return new View(u.id,u.username,u.displayName,u.role,u.subjectId,u.subjectName,u.department,u.version,true);}
    public List<View> list(int limit,int offset){Actor.requireAdmin();return db.query(PUBLIC_COLUMNS+JOIN+"ORDER BY a.created_at,a.user_id LIMIT ? OFFSET ?",VIEW,limit,offset);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<View> catalog(String keyword,String role,Boolean enabled,int limit,int offset){
        Actor.requireAdmin();validateQuery(keyword,role,limit,offset);
        String where="WHERE 1=1 ";var args=new ArrayList<Object>();
        if(!keyword.isBlank()){
            // LOCATE treats % and _ as literal characters, never SQL wildcards.
            where+="AND LOCATE(LOWER(?),LOWER(CONCAT_WS(' ',a.username,a.display_name,s.subject_name,s.owner_department)))>0 ";args.add(keyword.trim());
        }
        if(!role.isBlank()){where+="AND a.role=? ";args.add(role);}
        if(enabled!=null){where+="AND a.enabled=? ";args.add(enabled);}
        long total=db.queryForObject("SELECT COUNT(*) "+JOIN+where,Long.class,args.toArray());
        args.add(limit);args.add(offset);
        return new Page<>(db.query(PUBLIC_COLUMNS+JOIN+where+"ORDER BY a.created_at DESC,a.user_id DESC LIMIT ? OFFSET ?",VIEW,args.toArray()),total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<SubjectOption> subjectOptions(String keyword,String role,int limit,int offset){
        Actor.requireAdmin();validateQuery(keyword,role,limit,offset);
        if(!Set.of("PROVIDER","DEMAND").contains(role))invalid("请选择提供方或需求方角色");
        String where="WHERE status='ENABLED' AND subject_type=? AND LOCATE(LOWER(?),LOWER(CONCAT_WS(' ',subject_name,subject_code,owner_department)))>0 ";
        long total=db.queryForObject("SELECT COUNT(*) FROM sharing_subject "+where,Long.class,role,keyword.trim());
        return new Page<>(db.query("SELECT subject_id,subject_name,owner_department FROM sharing_subject "+where+"ORDER BY subject_name,subject_id LIMIT ? OFFSET ?",(r,n)->new SubjectOption(r.getString("subject_id"),r.getString("subject_name"),r.getString("owner_department")),role,keyword.trim(),limit,offset),total);
    }
    private void validateQuery(String keyword,String role,int limit,int offset){
        if(keyword==null||keyword.length()>120||role==null||(!role.isBlank()&&!Set.of("ADMIN","PROVIDER","DEMAND").contains(role))||limit<1||limit>200||offset<0||offset>1000000)invalid("账号查询参数不正确");
    }
    @Transactional public View create(String username,String name,String role,String subject,String password){
        lockRegistration();
        validate(username,name,role,subject,password);
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_registration WHERE username=? AND status='PENDING'",Integer.class,username)>0)invalid("该账号已有待审核注册申请，请先审核");
        String id="USR_"+UUID.randomUUID().toString().replace("-","");
        try{db.update("INSERT INTO workspace_account(user_id,username,display_name,password_hash,role,subject_id,created_at,updated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,username,name.trim(),encoder.encode(password),role,subject);}catch(org.springframework.dao.DuplicateKeyException e){throw new BusinessException(ErrorCode.BUSINESS_ERROR,"登录账号已存在");}
        return view(loadUserByUsername(username));
    }
    private void validate(String username,String name,String role,String subject,String password){
        if(username==null||!username.matches("[A-Za-z][A-Za-z0-9_.-]{2,63}")||name==null||name.isBlank()||name.length()>120||role==null||!Set.of("ADMIN","PROVIDER","DEMAND").contains(role))invalid("账号或用户名称格式不正确");
        validatePassword(password);
        if("ADMIN".equals(role)){if(subject!=null)invalid("管理员不关联普通主体");return;}
        if(subject==null||!subject.matches("SUB_[a-f0-9]{32}")||db.queryForObject("SELECT COUNT(*) FROM sharing_subject WHERE subject_id=? AND subject_type=? AND status='ENABLED'",Integer.class,subject,role)!=1)invalid("账号须关联启用且角色匹配的共享主体");
    }
    @Transactional public void status(String id,int version,boolean enabled){
        Actor.requireAdmin();if(id.equals(Actor.userId()))invalid("不能停用自己的当前管理账号");
        if(db.update("UPDATE workspace_account SET enabled=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND version=?",enabled,id,version)!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT,"账号已变化，请刷新");
    }
    @Transactional public void password(String id,int version,String password){
        Actor.requireAdmin();validatePassword(password);
        if(db.update("UPDATE workspace_account SET password_hash=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND version=?",encoder.encode(password),id,version)!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT,"账号已变化，请刷新");
    }
    @Transactional public void changeOwnPassword(String currentPassword,String newPassword){
        var identity=Actor.user();if(identity==null)Actor.deny();
        if(currentPassword==null||currentPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            invalid("当前密码不正确");
        validatePassword(newPassword);
        WorkspaceUser current;
        try{current=loadUserByUsername(identity.username);}catch(UsernameNotFoundException ex){throw new BusinessException(ErrorCode.UNAUTHENTICATED,"账号已失效，请重新登录");}
        if(current.version!=identity.version)throw new BusinessException(ErrorCode.VERSION_CONFLICT,"账号已变化，请重新登录");
        if(!encoder.matches(currentPassword,current.getPassword()))invalid("当前密码不正确");
        if(encoder.matches(newPassword,current.getPassword()))invalid("新密码不能与当前密码相同");
        // Bind the update to the authenticated account and its original version, never a client user ID.
        if(db.update("UPDATE workspace_account SET password_hash=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND version=? AND enabled=true",
            encoder.encode(newPassword),identity.id,identity.version)!=1)
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,"账号已变化，请重新登录");
    }
    private static void invalid(String msg){throw new BusinessException(ErrorCode.INVALID_ARGUMENT,msg);}
    void lockRegistration(){db.queryForObject("SELECT guard_id FROM workspace_registration_guard WHERE guard_id=1 FOR UPDATE",Integer.class);}
    @Transactional public void initializeAdministrator(String username,String password){
        lockRegistration();
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE role='ADMIN'",Integer.class)==0)
            create(username,"平台管理员","ADMIN",null,password);
    }
    static void validatePassword(String password){
        if(password==null||password.codePointCount(0,password.length())<12||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            invalid("新密码至少12个字符，且不超过72个UTF-8字节");
    }
}
