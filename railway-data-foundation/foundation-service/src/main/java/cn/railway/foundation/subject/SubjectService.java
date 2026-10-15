package cn.railway.foundation.subject;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Business subject registration only. A subject code is never an authentication token. */
@Service
public class SubjectService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Subject(String subjectId, String subjectCode, String subjectName, String subjectType,
                          String ownerDepartment, String description, String status, int version,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    private static final RowMapper<Subject> MAPPER = (r,n) -> new Subject(
            r.getString("subject_id"), r.getString("subject_code"), r.getString("subject_name"),
            r.getString("subject_type"), r.getString("owner_department"), r.getString("description"),
            r.getString("status"), r.getInt("version"), r.getTimestamp("created_at").toLocalDateTime(),
            r.getTimestamp("updated_at").toLocalDateTime());
    private final JdbcTemplate jdbc;
    public SubjectService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Subject> list(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0 || offset > 1000000)
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分页参数不合法");
        return jdbc.query("SELECT * FROM sharing_subject s WHERE "+Actor.subjectOptions("s")+" ORDER BY updated_at DESC,subject_id DESC LIMIT ? OFFSET ?", MAPPER, Actor.args(limit, offset));
    }
    public Subject get(String id) {
        var rows = jdbc.query("SELECT * FROM sharing_subject WHERE subject_id=?", MAPPER, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "共享主体不存在");
        return rows.get(0);
    }
    @Transactional
    public Subject create(SubjectRequest r) {
        String id = "SUB_" + UUID.randomUUID().toString().replace("-", "");
        var now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.update("INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,description,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'ENABLED',1,?,?)",
                    id, r.subjectCode(), r.subjectName().trim(), r.subjectType(), r.ownerDepartment().trim(), description(r.description()), now, now);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "业务编码已存在，请使用其他编码或查看已有主体");
        }
        return get(id);
    }
    @Transactional
    public Subject update(String id, SubjectUpdateRequest r) {
        get(id);
        requireUpdated(jdbc.update("UPDATE sharing_subject SET subject_name=?,owner_department=?,description=?,version=version+1,updated_at=? WHERE subject_id=? AND version=?",
                r.subjectName().trim(), r.ownerDepartment().trim(), description(r.description()), LocalDateTime.now(ZoneOffset.UTC), id, r.version()));
        return get(id);
    }
    @Transactional
    public Subject status(String id, SubjectStatusRequest r) {
        // Lock serializes simultaneous status writes, including requests for an already-set status.
        var rows = jdbc.query("SELECT * FROM sharing_subject WHERE subject_id=? FOR UPDATE", MAPPER, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "共享主体不存在");
        var current = rows.get(0);
        if (current.version() != r.version()) requireUpdated(0);
        if (current.status().equals(r.status())) return current;
        requireUpdated(jdbc.update("UPDATE sharing_subject SET status=?,version=version+1,updated_at=? WHERE subject_id=? AND version=?",
                r.status(), LocalDateTime.now(ZoneOffset.UTC), id, r.version()));
        return get(id);
    }
    @Transactional
    public void delete(String id) {
        jdbc.query("SELECT subject_id FROM sharing_subject WHERE subject_id=? FOR UPDATE",(r,n)->r.getString(1),id);
        var current=get(id);
        if (!"DISABLED".equals(current.status()))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"请先停用共享主体，再执行删除");
        Integer references=jdbc.queryForObject("SELECT COUNT(*) FROM sharing_relationship WHERE provider_subject_id=? OR demand_subject_id=?",Integer.class,id,id);
        if (references!=null && references>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"主体已被共享关系引用，不能删除；请保留登记与历史");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM caller_credential WHERE subject_id=?",Integer.class,id)>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"主体存在调用凭据历史，不能删除；请保留登记与历史");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM kafka_subscription_grant WHERE provider_subject_id=? OR demand_subject_id=?",Integer.class,id,id)>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"主体存在Kafka订阅审批历史，不能删除；请保留登记与历史");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_account WHERE subject_id=?",Integer.class,id)>0 || jdbc.queryForObject("SELECT COUNT(*) FROM source_system WHERE provider_subject_id=?",Integer.class,id)>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"主体仍关联用户账号或数据源，不能删除");
        if (jdbc.update("DELETE FROM sharing_subject WHERE subject_id=? AND status='DISABLED' AND version=?",id,current.version())!=1)
            requireUpdated(0);
    }
    private static String description(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void requireUpdated(int count) {
        if (count != 1) throw new BusinessException(ErrorCode.BUSINESS_ERROR, "主体信息已变化，请刷新后重新操作");
    }
}
