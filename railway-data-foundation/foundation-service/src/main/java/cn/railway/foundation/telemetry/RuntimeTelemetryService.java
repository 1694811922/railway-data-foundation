package cn.railway.foundation.telemetry;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Validator;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Trusted execution summaries only. Never accepts business rows, tokens or free-form error text. */
@Service
public class RuntimeTelemetryService {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(String reportId, int acceptedEvents, int duplicateEvents, boolean replayed, Instant receivedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(String eventId,String reportId,String traceId,String collectorId,String collectionPoint,
                        String relationshipId,String capabilityId,String subjectId,String resourceId,String policyId,
                        Integer relationshipVersion,Integer policyVersion,Instant startedAt,Instant completedAt,
                        String outcome,String reasonCode,int httpStatus,long recordCount,long byteCount,long durationMs,
                        String topic,Integer topicPartition,Long topicOffset,Instant receivedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(long total,List<Event> rows,int limit,int offset) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Window(Instant windowStart,Instant windowUntil,String collectionPoint,String state,String unit,
                         Long attempts,Long calls,Long messages,Long successes,Long denied,Long errors,
                         Long records,Long bytes,Double averageDurationMs,Double frequencyPerSecond,
                         long reports,long lateReports,long maximumReportDelayMs) {}
    public record Filter(String resourceId,String capabilityId,String relationshipId,String subjectId,String outcome,String traceId,
                         Instant from,Instant until,String collectionPoint) {}
    private record Query(String sql,List<Object> args) {}
    private record StoredReport(String collector,String digest,int accepted,int duplicates,Instant received) {}
    private static final Set<String> POINTS=Set.of("REST_PROVIDER","KAFKA_PRODUCER");
    private static final Set<String> DENIALS=Set.of("IDENTITY_INVALID","RELATIONSHIP_UNAVAILABLE","SUBJECT_UNAVAILABLE",
            "RELATIONSHIP_INACTIVE","RELATIONSHIP_EXPIRED","POLICY_UNAVAILABLE","FIELDS_FORBIDDEN","TIME_FORBIDDEN",
            "CAPABILITY_UNAVAILABLE","INVALID_REQUEST","OPERATOR_FORBIDDEN");
    private static final Set<String> ERRORS=Set.of("SOURCE_UNAVAILABLE","CONTROL_UNAVAILABLE","SERIALIZATION_ERROR","INTERNAL_ERROR","KAFKA_UNAVAILABLE");
    private static final String EVENT_COLUMNS="event_id,report_id,trace_id,collector_id,collection_point,relationship_id,capability_id,subject_id,resource_id,policy_id,relationship_version,policy_version,started_at,completed_at,outcome,reason_code,http_status,record_count,byte_count,duration_ms,topic,topic_partition,topic_offset,received_at";
    private static final RowMapper<Event> MAPPER=(r,n)->new Event(r.getString("event_id"),r.getString("report_id"),r.getString("trace_id"),
            r.getString("collector_id"),r.getString("collection_point"),r.getString("relationship_id"),r.getString("capability_id"),
            r.getString("subject_id"),r.getString("resource_id"),r.getString("policy_id"),(Integer)r.getObject("relationship_version"),
            (Integer)r.getObject("policy_version"),time(r,"started_at"),time(r,"completed_at"),r.getString("outcome"),
            r.getString("reason_code"),r.getInt("http_status"),r.getLong("record_count"),r.getLong("byte_count"),r.getLong("duration_ms"),
            r.getString("topic"),(Integer)r.getObject("topic_partition"),(Long)r.getObject("topic_offset"),time(r,"received_at"));
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Validator validator;
    private final String collector;
    private final String topic;
    public RuntimeTelemetryService(JdbcTemplate db,ObjectMapper json,Validator validator,Environment env) {
        this.db=db;this.json=json;this.validator=validator;
        collector=env.getProperty("PROVIDER_TELEMETRY_NODE_ID","provider-local");
        topic=env.getProperty("FOUNDATION_KAFKA_TOPIC","port.container.event.v1");
        if(!collector.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalStateException("采集节点配置不合法");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt accept(RuntimeReportRequest report) {
        validate(report);
        Instant received=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // Sort the digest and acquisition order: equivalent retries can reorder events, and concurrent batches lock consistently.
        var observations=report.events().stream().sorted(Comparator.comparing(RuntimeReportRequest.Observation::eventId)).toList();
        var normalized=new RuntimeReportRequest(report.reportId(),report.collectionPoint(),report.windowStart(),report.windowUntil(),report.windowClosed(),observations);
        String reportDigest=digest(List.of(collector,normalized));
        // MySQL INSERT IGNORE returns 1 only for a new row. All values and constraints are validated before this statement.
        // The unique insert waits for an in-flight writer. Committed reports/events are immutable thereafter.
        // Do not upgrade duplicate-key shared locks with SELECT FOR UPDATE: parallel retries would deadlock.
        int inserted=db.update("INSERT IGNORE INTO sharing_runtime_report(report_id,collector_id,collection_point,window_start,window_until,window_closed,content_digest,accepted_events,duplicate_events,received_at) VALUES(?,?,?,?,?,?,?,0,0,?)",
                report.reportId(),collector,report.collectionPoint(),sql(report.windowStart()),sql(report.windowUntil()),report.windowClosed(),reportDigest,sql(received));
        var stored=db.query("SELECT * FROM sharing_runtime_report WHERE report_id=?",(r,n)->new StoredReport(r.getString("collector_id"),r.getString("content_digest"),r.getInt("accepted_events"),r.getInt("duplicate_events"),time(r,"received_at")),report.reportId());
        if(stored.isEmpty())throw new IllegalStateException("摘要报告未保存");
        var old=stored.get(0);
        if(!old.collector().equals(collector)||!old.digest().equals(reportDigest))throw conflict();
        if(inserted==0)return new Receipt(report.reportId(),old.accepted(),old.duplicates(),true,old.received());
        int accepted=0,duplicates=0;
        for(var event:observations) {
            String eventDigest=digest(List.of(collector,report.collectionPoint(),event));
            int created=db.update("INSERT IGNORE INTO sharing_runtime_event("+EVENT_COLUMNS+",content_digest) VALUES("+"?,".repeat(24)+"?)",
                    event.eventId(),report.reportId(),event.traceId(),collector,report.collectionPoint(),event.relationshipId(),event.capabilityId(),
                    event.subjectId(),event.resourceId(),event.policyId(),event.relationshipVersion(),event.policyVersion(),sql(event.startedAt()),
                    sql(event.completedAt()),event.outcome(),event.reasonCode(),event.httpStatus(),event.recordCount(),event.byteCount(),event.durationMs(),
                    event.topic(),event.topicPartition(),event.topicOffset(),sql(received),eventDigest);
            if(created==1){accepted++;continue;}
            var hashes=db.query("SELECT content_digest FROM sharing_runtime_event WHERE event_id=?",(r,n)->r.getString(1),event.eventId());
            // A reused trace with a different event ID also fails; an unknown ignored insert is never silently counted.
            if(hashes.isEmpty()||!hashes.get(0).equals(eventDigest))throw conflict();
            duplicates++;
        }
        db.update("UPDATE sharing_runtime_report SET accepted_events=?,duplicate_events=? WHERE report_id=?",accepted,duplicates,report.reportId());
        return new Receipt(report.reportId(),accepted,duplicates,false,received);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(Filter filter,int limit,int offset) {
        if(limit<1||limit>200||offset<0||offset>1000000)throw invalid("分页参数不合法");
        Query q=query(filter);
        long total=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM sharing_runtime_event WHERE "+q.sql(),Long.class,q.args().toArray()));
        var args=new ArrayList<>(q.args());args.add(limit);args.add(offset);
        return new Page(total,db.query("SELECT "+EVENT_COLUMNS+" FROM sharing_runtime_event WHERE "+q.sql()+" ORDER BY completed_at DESC,event_id DESC LIMIT ? OFFSET ?",MAPPER,args.toArray()),limit,offset);
    }
    public Event get(String id) {
        requireId(id,null);
        var rows=db.query("SELECT "+EVENT_COLUMNS+" FROM sharing_runtime_event WHERE "+Actor.telemetry()+" AND event_id=? AND collector_id=?",MAPPER,Actor.args(id,collector));
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"运行事件不存在");
        return rows.get(0);
    }
    public List<Event> trace(String id) {
        requireId(id,null);
        var rows=db.query("SELECT "+EVENT_COLUMNS+" FROM sharing_runtime_event WHERE "+Actor.telemetry()+" AND trace_id=? AND collector_id=? ORDER BY started_at,event_id",MAPPER,Actor.args(id,collector));
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND,"追踪记录不存在");
        return rows;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Window> windows(Filter filter) {
        requirePoint(filter.collectionPoint());
        Instant until=filter.until()==null?Instant.now().truncatedTo(ChronoUnit.MINUTES):filter.until();
        Instant from=filter.from()==null?until.minus(15,ChronoUnit.MINUTES):filter.from();
        validateTime(from);validateTime(until);
        if(!from.equals(from.truncatedTo(ChronoUnit.MINUTES))||!until.equals(until.truncatedTo(ChronoUnit.MINUTES))
                ||!from.isBefore(until)||Duration.between(from,until).toMinutes()>1440)throw invalid("计量查询需使用UTC整分钟范围，最多一天");
        Query q=query(new Filter(filter.resourceId(),filter.capabilityId(),filter.relationshipId(),filter.subjectId(),null,null,from,until,filter.collectionPoint()));
        record Counts(long attempts,long successes,long denied,long errors,long records,long bytes,double duration) {}
        var counts=new HashMap<Instant,Counts>();
        db.query("SELECT DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:00') AS minute,COUNT(*) AS attempts,SUM(outcome='SUCCESS') AS successes,SUM(outcome='DENIED') AS denied,SUM(outcome='ERROR') AS errors,SUM(record_count) AS records,SUM(byte_count) AS bytes,AVG(duration_ms) AS duration FROM sharing_runtime_event WHERE "+q.sql()+" GROUP BY minute",
                (org.springframework.jdbc.core.RowCallbackHandler)r->counts.put(LocalDateTime.parse(r.getString("minute").replace(' ','T')).toInstant(ZoneOffset.UTC),new Counts(r.getLong("attempts"),r.getLong("successes"),r.getLong("denied"),r.getLong("errors"),r.getLong("records"),r.getLong("bytes"),r.getDouble("duration"))),q.args().toArray());
        record Reports(long count,boolean closed,long late,long delay) {}
        var reports=new HashMap<Instant,Reports>();
        if(Actor.admin())db.query("SELECT window_start,COUNT(*) AS count,MAX(window_closed) AS closed,SUM(received_at>window_until) AS late,GREATEST(0,MAX(TIMESTAMPDIFF(MICROSECOND,window_until,received_at) DIV 1000)) AS delay FROM sharing_runtime_report WHERE collector_id=? AND collection_point=? AND window_start>=? AND window_start<? GROUP BY window_start",
                (org.springframework.jdbc.core.RowCallbackHandler)r->reports.put(time(r,"window_start"),new Reports(r.getLong("count"),r.getBoolean("closed"),r.getLong("late"),r.getLong("delay"))),collector,filter.collectionPoint(),sql(from),sql(until));
        boolean scoped=!Actor.admin()||filter.resourceId()!=null||filter.capabilityId()!=null||filter.relationshipId()!=null||filter.subjectId()!=null;
        boolean rest=filter.collectionPoint().equals("REST_PROVIDER");var result=new ArrayList<Window>();
        for(Instant start=from;start.isBefore(until);start=start.plusSeconds(60)) {
            var c=counts.get(start);var r=reports.get(start);
            String state=!Actor.admin()?(c==null?"UNKNOWN":"PARTIAL"):r==null?"MISSING":r.closed()?"CLOSED":"PARTIAL";
            if(scoped&&c==null)state="UNKNOWN";
            // Empty open reports do not establish an observed zero; explicitly closed global windows do.
            boolean known=c!=null||(!scoped&&r!=null&&r.closed());
            Long attempts=known?(c==null?0L:c.attempts()):null,successes=known?(c==null?0L:c.successes()):null;
            result.add(new Window(start,start.plusSeconds(60),filter.collectionPoint(),state,rest?"CALL":"MESSAGE",
                    attempts,rest?attempts:null,rest?null:successes,successes,known?(c==null?0L:c.denied()):null,
                    known?(c==null?0L:c.errors()):null,known?(c==null?0L:c.records()):null,known?(c==null?0L:c.bytes()):null,
                    c==null?null:c.duration(),known?(rest?attempts:successes)/60.0:null,r==null?0:r.count(),r==null?0:r.late(),r==null?0:r.delay()));
        }
        return result;
    }

    private Query query(Filter f) {
        var clauses=new ArrayList<String>();clauses.add(Actor.telemetry());clauses.add("collector_id=?");var args=new ArrayList<Object>(Arrays.asList(Actor.args(collector)));
        add(clauses,args,"resource_id",f.resourceId(),"RES");add(clauses,args,"capability_id",f.capabilityId(),"CAP");
        add(clauses,args,"relationship_id",f.relationshipId(),"REL");add(clauses,args,"subject_id",f.subjectId(),"SUB");add(clauses,args,"trace_id",f.traceId(),null);
        if(f.outcome()!=null){if(!Set.of("SUCCESS","DENIED","ERROR").contains(f.outcome()))throw invalid("结果筛选不合法");clauses.add("outcome=?");args.add(f.outcome());}
        if(f.collectionPoint()!=null){requirePoint(f.collectionPoint());clauses.add("collection_point=?");args.add(f.collectionPoint());}
        if(f.from()!=null){validateTime(f.from());clauses.add("completed_at>=?");args.add(sql(f.from()));}
        if(f.until()!=null){validateTime(f.until());clauses.add("completed_at<?");args.add(sql(f.until()));}
        if(f.from()!=null&&f.until()!=null&&!f.from().isBefore(f.until()))throw invalid("查询起止时间不合法");
        return new Query(String.join(" AND ",clauses),args);
    }
    private static void add(List<String> clauses,List<Object> args,String column,String value,String prefix) {
        if(value!=null){requireId(value,prefix);clauses.add(column+"=?");args.add(value);}
    }
    private void validate(RuntimeReportRequest r) {
        if(r==null||!validator.validate(r).isEmpty())throw invalid("摘要报告字段不合法");
        validateTime(r.windowStart());validateTime(r.windowUntil());
        Instant now=Instant.now();
        if(!r.windowStart().equals(r.windowStart().truncatedTo(ChronoUnit.MINUTES))||!r.windowUntil().equals(r.windowStart().plusSeconds(60))
                ||r.windowStart().isAfter(now.plusSeconds(5))||(r.windowClosed()&&r.windowUntil().isAfter(now)))throw invalid("摘要报告时间窗口不合法");
        var ids=new HashSet<String>();var traces=new HashSet<String>();
        for(var e:r.events()) {
            validateTime(e.startedAt());validateTime(e.completedAt());
            if(!ids.add(e.eventId())||!traces.add(e.traceId()))throw invalid("单份报告事件或追踪编号重复");
            if(e.startedAt().isAfter(e.completedAt())||e.completedAt().isBefore(r.windowStart())||!e.completedAt().isBefore(r.windowUntil())
                    ||e.completedAt().isAfter(now.plusSeconds(5)))throw invalid("事件时间不属于报告窗口");
            if((e.policyId()==null)!=(e.policyVersion()==null)||(e.relationshipVersion()!=null&&e.relationshipId()==null))throw invalid("策略或关系版本不完整");
            boolean rest=r.collectionPoint().equals("REST_PROVIDER"),success=e.outcome().equals("SUCCESS");
            if(success) {
                if(!e.reasonCode().equals("COMPLETED")||rest&&e.httpStatus()!=200||!rest&&!Set.of(200L,202L).contains(e.httpStatus()))throw invalid("成功摘要状态不合法");
                if(rest&&(e.relationshipId()==null||e.capabilityId()==null||e.subjectId()==null||e.resourceId()==null||e.policyId()==null||e.relationshipVersion()==null))throw invalid("成功查询缺少授权定位信息");
            } else {
                if(e.recordCount()!=0||e.byteCount()!=0)throw invalid("失败或拒绝不能计入共享记录和字节");
                if(e.outcome().equals("DENIED")) {
                    if(!DENIALS.contains(e.reasonCode())||!Set.of(400L,401L,403L).contains(e.httpStatus()))throw invalid("拒绝摘要状态不合法");
                } else if(!ERRORS.contains(e.reasonCode())||e.httpStatus()<500&&!(e.httpStatus()==200&&e.reasonCode().equals("SERIALIZATION_ERROR")))throw invalid("异常摘要状态不合法");
            }
            if(rest) {
                if(e.topic()!=null||e.topicPartition()!=null||e.topicOffset()!=null)throw invalid("接口查询不能上报消息位点");
            } else {
                if(e.topic()!=null&&!topic.equals(e.topic()))throw invalid("消息主题不匹配采集配置");
                if(success&&(e.recordCount()!=1||e.topic()==null||e.topicPartition()==null||e.topicOffset()==null))throw invalid("成功消息缺少发送回执");
                if(!success&&(e.topicPartition()!=null||e.topicOffset()!=null))throw invalid("失败消息不能登记成功位点");
            }
        }
    }
    private String digest(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("摘要规范化失败");}
    }
    private static void requirePoint(String point){if(point==null||!POINTS.contains(point))throw invalid("采集位置不合法");}
    private static void requireId(String id,String prefix){if(id==null||!id.matches(prefix==null?RuntimeReportRequest.UUID:prefix+"_[a-f0-9]{32}"))throw invalid("定位编号格式不合法");}
    private static void validateTime(Instant time){if(time==null||time.getNano()%1000000!=0||time.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||!time.isBefore(Instant.parse("2100-01-01T00:00:00Z")))throw invalid("时间需在2000至2099年且精度不超过毫秒");}
    private static LocalDateTime sql(Instant instant){return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static Instant time(ResultSet r,String column)throws SQLException {var value=r.getObject(column,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_ARGUMENT,message);}
    private static BusinessException conflict(){return new BusinessException(ErrorCode.BUSINESS_ERROR,"报告、事件或追踪编号与已接收内容冲突");}
}
