package cn.railway.foundation.metadata;

import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import cn.railway.foundation.source.SourceEntity;
import cn.railway.foundation.credential.SourceCredential;

@Component
public class MysqlSourceConnectionProvider implements SourceConnectionProvider {
    @Override public boolean supports(String type) { return "MYSQL".equals(type); }
    @Override public void validate(SourceEntity source) {
        if (source.port==null) throw new IllegalArgumentException();
        new MysqlConnectionSettings(source.host,source.port,source.databaseName,source.username,"",source.sslMode);
    }
    @Override public Connection openConnection(SourceEntity source, SourceCredential credential) throws SQLException {
        validate(source);
        var settings=new MysqlConnectionSettings(source.host,source.port,source.databaseName,credential.username(),credential.password(),source.sslMode);
        var properties=new Properties();
        properties.setProperty("user",settings.username);
        properties.setProperty("password",settings.password);
        properties.setProperty("connectTimeout","3000");
        properties.setProperty("socketTimeout","5000");
        properties.setProperty("useUnicode","true");
        properties.setProperty("characterEncoding","UTF-8");
        properties.setProperty("allowMultiQueries","false");
        properties.setProperty("allowLoadLocalInfile","false");
        properties.setProperty("allowUrlInLocalInfile","false");
        properties.setProperty("sslMode",settings.sslMode);
        properties.setProperty("allowPublicKeyRetrieval",Boolean.toString(settings.sslMode.equals("DISABLED")));
        var connection=DriverManager.getConnection("jdbc:mysql://"+settings.host+":"+settings.port+"/"+settings.database,properties);
        try { connection.setReadOnly(true); return connection; }
        catch (SQLException ex) { connection.close(); throw ex; }
    }
}
