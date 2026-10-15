package cn.railway.foundation.security;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class InitialAdministrator {
    @Bean ApplicationRunner initializeFirstAdministrator(JdbcTemplate db,Environment env,AccountService accounts){return args->{
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE role='ADMIN'",Integer.class)>0)return;
        String password=env.getProperty("FOUNDATION_BOOTSTRAP_ADMIN_PASSWORD");
        if(password==null||password.isBlank())return; // No baked-in account or universal password.
        accounts.initializeAdministrator(env.getProperty("FOUNDATION_BOOTSTRAP_ADMIN_USERNAME","foundation_admin"),password);
    };}
}
