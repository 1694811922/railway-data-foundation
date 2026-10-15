package cn.railway.foundation.source;

import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.metadata.ConnectionTest;
import cn.railway.foundation.metadata.MetadataAdapterFactory;
import cn.railway.foundation.metadata.MysqlConnectionSettings;
import cn.railway.foundation.metadata.SourceConnectionFactory;
import cn.railway.foundation.credential.CredentialService;
import org.springframework.core.env.Environment;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class SourceService {
    private final SourceMapper mapper;
    private final MetadataAdapterFactory adapters;
    private final CredentialService credentials;
    private final Environment environment;
    private final SourceConnectionFactory connections;
    private final JdbcTemplate db;
    public SourceService(SourceMapper mapper, MetadataAdapterFactory adapters, CredentialService credentials, Environment environment, SourceConnectionFactory connections, JdbcTemplate db) {
        this.mapper=mapper; this.adapters=adapters; this.credentials=credentials; this.environment=environment; this.connections=connections; this.db=db;
    }

    @Transactional
    public SourceView create(SourceRequest request) {
        var source = new SourceEntity();
        source.sourceId = "SRC_" + UUID.randomUUID().toString().replace("-", "");
        source.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        source.providerSubjectId=Actor.provider()?Actor.subject():request.providerSubjectId();
        validateOwner(source.providerSubjectId);
        apply(source, request);
        mapper.insert(source);
        return view(source);
    }

    public List<SourceView> list(int limit, int offset) {
        // Both numbers are bounded and validated by the controller; no user SQL is accepted.
        return mapper.selectList(new QueryWrapper<SourceEntity>().eq(!Actor.admin(),"provider_subject_id",Actor.subject()).orderByAsc("created_at", "source_id")
                .last("LIMIT " + limit + " OFFSET " + offset)).stream().map(this::view).toList();
    }

    public SourceView get(String id) { return view(require(id)); }

    @Transactional
    public SourceView update(String id, SourceRequest request) {
        var source = require(id);
        apply(source, request);
        mapper.updateById(source);
        return view(source);
    }

    public ConnectionTest test(String id) { return adapters.create(require(id)).probeConnection(); }

    @Transactional
    public SourceView status(String id, String status) {
        var source=require(id); source.status=status; source.updatedAt=LocalDateTime.now(ZoneOffset.UTC);
        mapper.updateById(source); return view(source);
    }

    @Transactional
    public void delete(String id) {
        var source=require(id);
        if (!"DISABLED".equals(source.status))
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"请先停用数据源，再执行删除");
        Integer scans=db.queryForObject("SELECT COUNT(*) FROM metadata_scan_task WHERE source_id=?",Integer.class,id);
        if (scans!=null && scans>0)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"数据源已有扫描记录，不能删除；可保持停用以保留历史");
        String credentialRef=source.credentialRef;
        if (mapper.deleteById(id)!=1) throw new BusinessException(ErrorCode.NOT_FOUND,"数据源不存在");
        if (credentialRef!=null) credentials.deleteCredential(credentialRef);
    }

    private SourceEntity require(String id) {
        SourceEntity source = mapper.selectById(id);
        if (source == null) throw new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在");
        return source;
    }

    private void validateOwner(String id){
        if(id!=null && db.queryForObject("SELECT COUNT(*) FROM sharing_subject WHERE subject_id=? AND subject_type='PROVIDER' AND status='ENABLED'",Integer.class,id)!=1)
            throw invalid("请选择启用的提供方主体");
    }
    private void apply(SourceEntity source, SourceRequest request) {
        if(request.providerSubjectId()!=null && !java.util.Objects.equals(request.providerSubjectId(),source.providerSubjectId))Actor.deny();
        if(!Actor.admin() && request.connectionEnvPrefix()!=null)Actor.deny();
        source.sourceName = request.sourceName().strip();
        source.sourceType = request.sourceType();
        source.description = request.description() == null ? "" : request.description().strip();
        source.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        source.status=request.status()==null ? (source.status==null ? "ENABLED" : source.status) : request.status();
        if (request.connectionEnvPrefix()!=null) {
            if (source.credentialRef!=null || request.host()!=null || request.port()!=null || request.databaseName()!=null
                    || request.username()!=null || request.sslMode()!=null || request.password()!=null)
                throw invalid("环境变量引用不能与动态连接信息混用");
            source.connectionEnvPrefix=request.connectionEnvPrefix();
            credentials.fromEnvironment(source.connectionEnvPrefix);
        } else {
            String password=request.password();
            if ((password==null || password.isEmpty()) && source.credentialRef==null)
                throw invalid("新增或转换数据源时必须提供密码");
            if (request.port()==null) throw invalid("请填写完整的连接信息");
            String ssl=request.sslMode()==null ? "PREFERRED" : request.sslMode();
            try {
                new MysqlConnectionSettings(request.host(),request.port(),request.databaseName(),request.username(),
                        password==null ? "" : password,ssl);
            } catch (IllegalArgumentException ex) { throw invalid("连接信息格式不合法"); }
            source.host=request.host(); source.port=request.port(); source.databaseName=request.databaseName();
            source.username=request.username(); source.sslMode=ssl; source.connectionEnvPrefix=null;
            if (password!=null && !password.isEmpty()) source.credentialRef=credentials.saveCredential(source.credentialRef,source.username,password);
            else credentials.updateUsername(source.credentialRef,source.username);
        }
        // Configuration and credential validation only; connectivity is checked by the explicit test operation.
        connections.validate(source);
    }

    private SourceView view(SourceEntity source) {
        if (source.credentialRef!=null) return SourceView.from(source);
        String prefix=source.connectionEnvPrefix;
        Integer port=null;
        try { port=Integer.valueOf(environment.getProperty(prefix+"_PORT","")); } catch (NumberFormatException ignored) { }
        return new SourceView(source.sourceId,source.sourceName,source.sourceType,prefix,source.description,
                environment.getProperty(prefix+"_HOST"),port,environment.getProperty(prefix+"_DATABASE"),
                environment.getProperty(prefix+"_USER"),environment.getProperty(prefix+"_SSL_MODE","PREFERRED"),
                null,"ENV_REFERENCE",source.status,source.createdAt,source.updatedAt,source.providerSubjectId);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_ARGUMENT,message); }
}
