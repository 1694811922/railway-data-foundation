package cn.railway.demand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.util.UUID;

/** A transaction joins the caller's data transaction, or creates one for an acknowledgement. */
@Component
public class TransferJournal {
    private final JdbcTemplate db;
    public TransferJournal(JdbcTemplate db){this.db=db;}
    @Transactional
    public void append(String sql,Object... args){
        var key=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);
        if(key.getKey()==null)throw new IllegalStateException("日志编号未生成");
        db.update("INSERT INTO receiving_monitor_outbox(log_id,event_id) VALUES(?,?)",key.getKey().longValue(),UUID.randomUUID().toString());
    }
}
