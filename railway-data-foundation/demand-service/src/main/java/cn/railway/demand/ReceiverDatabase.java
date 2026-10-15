package cn.railway.demand;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.env.Environment;
import java.util.Set;
@Configuration
public class ReceiverDatabase {
    @Bean FlywayMigrationStrategy isolatedMigration(Environment env){return flyway->{
        String expected=env.getProperty("DEMAND_DB_NAME","demand_receiver_db");
        if(!expected.matches("[A-Za-z0-9_]{1,64}")||Set.of("foundation_db","foundation_test","port_source_db","port_source_test","mysql","sys","information_schema","performance_schema").contains(expected))throw new IllegalStateException("需求方必须使用独立数据库");
        try(var c=flyway.getConfiguration().getDataSource().getConnection()){if(!expected.equals(c.getCatalog()))throw new IllegalStateException("需求方数据库名称与配置不一致");}catch(java.sql.SQLException e){throw new IllegalStateException("需求方数据库连接失败");}
        flyway.migrate();
    };}
}
