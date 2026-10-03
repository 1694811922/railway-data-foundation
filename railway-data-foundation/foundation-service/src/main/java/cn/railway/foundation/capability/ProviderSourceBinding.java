package cn.railway.foundation.capability;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.metadata.SourceConnectionFactory;
import cn.railway.foundation.source.SourceMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Compares non-secret connection metadata; a dynamic Source need not have an environment prefix. */
@Component
public class ProviderSourceBinding {
    public record Target(String host, int port, String databaseName, String physicalTable) {}
    private record Resource(String sourceId, String schemaName, String physicalTable, String status) {}
    private final JdbcTemplate db;
    private final SourceMapper sources;
    private final SourceConnectionFactory connections;
    private final Environment environment;

    public ProviderSourceBinding(JdbcTemplate db, SourceMapper sources, SourceConnectionFactory connections, Environment environment) {
        this.db = db;
        this.sources = sources;
        this.connections = connections;
        this.environment = environment;
    }

    public Target requireSupported(String resourceId) {
        var rows = db.query("SELECT source_id,schema_name,physical_table,status FROM data_resource WHERE resource_id=?",
                (rs, n) -> new Resource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), resourceId);
        if (rows.isEmpty()) throw invalid("所属资源不存在");
        var resource = rows.get(0);
        if (!"PUBLISHED".equals(resource.status())) throw invalid("所属资源尚未发布");
        var source = sources.selectById(resource.sourceId());
        if (source == null) throw invalid("所属数据源不存在");
        if (!"ENABLED".equals(source.status)) throw invalid("所属数据源已停用");
        if (!"MYSQL".equals(source.sourceType)) throw invalid("Provider暂不支持该数据源类型");
        var config = connections.configuration(source);
        String prefix = environment.getProperty("PROVIDER_SOURCE_ENV_PREFIX", "");
        if (!prefix.matches("SOURCE_[A-Z][A-Z0-9_]*")) throw invalid("Provider来源配置不合法");
        String host = environment.getProperty(prefix + "_HOST", "");
        String database = environment.getProperty(prefix + "_DATABASE", "");
        final int port;
        try {
            port = Integer.parseInt(environment.getProperty(prefix + "_PORT", ""));
        } catch (NumberFormatException ex) { throw invalid("Provider来源配置不完整"); }
        if (host.isBlank() || database.isBlank() || port < 1 || port > 65535)
            throw invalid("Provider来源配置不完整");
        if (!"container_event".equals(resource.physicalTable()) || !database.equals(resource.schemaName())
                || !database.equals(config.databaseName) || !host.equalsIgnoreCase(config.host)
                || !Integer.valueOf(port).equals(config.port))
            throw invalid("能力关联资源与Provider来源不一致，请核对地址、端口、数据库和物理表");
        return new Target(config.host, config.port, config.databaseName, resource.physicalTable());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BUSINESS_ERROR, message);
    }
}
