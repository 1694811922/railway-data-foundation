package cn.railway.provider;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ContainerEventService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(long id,String containerNo,String eventType,LocalDateTime eventTime,String portCode,
            String yardCode,String trainNo,String operationStatus,String equipmentNo,String sourceSystem,
            LocalDateTime createdAt,LocalDateTime updatedAt) {}
    private final JdbcTemplate db;
    public ContainerEventService(JdbcTemplate db) { this.db=db; }

    /** Runs only at the Provider. Foundation never receives rows or a row count. */
    public List<Event> search(String containerNo,String trainNo,String eventType,LocalDateTime from,
            LocalDateTime to,int limit,int offset) {
        if (from!=null && to!=null && !from.isBefore(to)) throw new IllegalArgumentException("时间范围不合法");
        StringBuilder sql=new StringBuilder("SELECT id,container_no,event_type,event_time,port_code,yard_code,train_no,operation_status,equipment_no,source_system,created_at,updated_at FROM container_event WHERE 1=1");
        List<Object> args=new ArrayList<>();
        if (containerNo!=null) { sql.append(" AND container_no=?"); args.add(containerNo); }
        if (trainNo!=null) { sql.append(" AND train_no=?"); args.add(trainNo); }
        if (eventType!=null) { sql.append(" AND event_type=?"); args.add(eventType); }
        if (from!=null) { sql.append(" AND event_time>=?"); args.add(from); }
        if (to!=null) { sql.append(" AND event_time<?"); args.add(to); }
        sql.append(" ORDER BY event_time DESC,id DESC LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return db.query(sql.toString(),(rs,n)->row(rs),args.toArray());
    }

    /** Checks SELECT permission and table availability without selecting a business row. */
    public void probe() { db.query("SELECT 1 FROM container_event LIMIT 0",rs->null); }

    public Event findById(long id) {
        var rows=db.query("SELECT id,container_no,event_type,event_time,port_code,yard_code,train_no,operation_status,equipment_no,source_system,created_at,updated_at FROM container_event WHERE id=?",
                (rs,n)->row(rs),id);
        return rows.isEmpty()?null:rows.get(0);
    }

    private static Event row(ResultSet rs) throws SQLException {
        return new Event(rs.getLong("id"),rs.getString("container_no"),rs.getString("event_type"),
                rs.getTimestamp("event_time").toLocalDateTime(),rs.getString("port_code"),rs.getString("yard_code"),
                rs.getString("train_no"),rs.getString("operation_status"),rs.getString("equipment_no"),
                rs.getString("source_system"),rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
