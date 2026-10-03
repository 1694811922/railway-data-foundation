package cn.railway.foundation.metadata;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.credential.CredentialService;
import cn.railway.foundation.source.SourceEntity;
import cn.railway.foundation.source.SourceMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@Component
public class SourceConnectionFactory {
    private final Environment environment;
    private final CredentialService credentials;
    private final List<SourceConnectionProvider> providers;
    private final SourceMapper sources;
    public SourceConnectionFactory(Environment environment, CredentialService credentials, List<SourceConnectionProvider> providers, SourceMapper sources) {
        this.environment=environment; this.credentials=credentials; this.providers=providers; this.sources=sources;
    }
    /** Legacy references are resolved to the same connection metadata shape as dynamic sources. */
    public SourceEntity configuration(SourceEntity source) {
        try {
            if (source.credentialRef!=null) return source;
            String prefix=source.connectionEnvPrefix;
            if (prefix==null || !prefix.matches("SOURCE_[A-Z][A-Z0-9_]*")) throw new IllegalArgumentException();
            var resolved=new SourceEntity();
            resolved.sourceId=source.sourceId; resolved.sourceType=source.sourceType; resolved.status=source.status;
            resolved.host=required(prefix+"_HOST"); resolved.port=Integer.valueOf(required(prefix+"_PORT"));
            resolved.databaseName=required(prefix+"_DATABASE"); resolved.username=required(prefix+"_USER");
            resolved.sslMode=environment.getProperty(prefix+"_SSL_MODE","PREFERRED");
            return resolved;
        } catch (IllegalArgumentException | NullPointerException ex) { throw invalid("数据源连接配置缺失或格式不合法"); }
    }
    public void validate(SourceEntity source) {
        try { provider(source.sourceType).validate(configuration(source)); }
        catch (IllegalArgumentException ex) { throw invalid("数据源连接信息格式不合法"); }
    }
    public Connection openConnection(String sourceId) throws SQLException {
        SourceEntity source=sources.selectById(sourceId);
        if (source==null) throw new BusinessException(ErrorCode.NOT_FOUND,"数据源不存在");
        return openConnection(source);
    }
    /** A scan uses one configuration snapshot, avoiding cross-database results during concurrent edits. */
    public Connection openConnection(SourceEntity source) throws SQLException {
        if ("DISABLED".equals(source.status)) throw invalid("数据源已停用，请先启用后再测试或扫描");
        validate(source);
        var credential=source.credentialRef==null ? credentials.fromEnvironment(source.connectionEnvPrefix)
                : credentials.getCredential(source.credentialRef);
        var resolved=configuration(source);
        if (!resolved.username.equals(credential.username())) throw invalid("数据源账号与凭据配置不一致");
        return provider(source.sourceType).openConnection(resolved,credential);
    }
    private SourceConnectionProvider provider(String type) {
        return providers.stream().filter(p->p.supports(type)).findFirst().orElseThrow(()->invalid("数据源类型暂不支持"));
    }
    private String required(String name) {
        String value=environment.getProperty(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_ARGUMENT,message); }
}
