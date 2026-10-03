package cn.railway.foundation.metadata;

import java.sql.Connection;
import java.sql.SQLException;
import cn.railway.foundation.source.SourceEntity;
import cn.railway.foundation.credential.SourceCredential;

public interface SourceConnectionProvider {
    boolean supports(String sourceType);
    void validate(SourceEntity source);
    Connection openConnection(SourceEntity source, SourceCredential credential) throws SQLException;
}
