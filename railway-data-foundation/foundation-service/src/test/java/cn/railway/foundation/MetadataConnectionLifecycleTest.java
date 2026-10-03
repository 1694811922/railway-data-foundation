package cn.railway.foundation;

import cn.railway.foundation.metadata.*;
import cn.railway.foundation.source.SourceEntity;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.net.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MetadataConnectionLifecycleTest {
    @Test void probeClosesConnectionAndMapsFailureSafely() throws Exception {
        var source=new SourceEntity();
        var factory=mock(SourceConnectionFactory.class);
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(factory.openConnection(source)).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
        when(connection.isValid(3)).thenReturn(true);
        var adapter=new MysqlMetadataAdapter(source,"db",factory);
        assertThat(adapter.probeConnection().connected()).isTrue();verify(connection).close();
        when(factory.openConnection(source)).thenThrow(new SQLException("password=secret","28000",1045));
        var failure=adapter.probeConnection();
        assertThat(failure.connected()).isFalse();assertThat(failure.reason()).isEqualTo("用户名或密码错误");
        assertThat(failure.toString()).doesNotContain("secret");
    }
    @Test void closesScanConnectionsStatementsAndResultsOnSuccessAndFailure() throws Exception {
        var source=new SourceEntity();var factory=mock(SourceConnectionFactory.class);
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(factory.openConnection(source)).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        var adapter=new MysqlMetadataAdapter(source,"db",factory);
        assertThat(adapter.scanTables()).isEmpty();verify(rows).close();verify(statement).close();verify(connection).close();
        when(statement.executeQuery()).thenThrow(new SQLException("password=secret","42000",1142));
        assertThatThrownBy(adapter::scanTables).hasMessageContaining("当前账号权限不足").hasMessageNotContaining("secret");
        verify(statement,times(2)).close();verify(connection,times(2)).close();
    }
    @Test void classifiesMissingDatabasePortAndTimeoutWithoutRawDriverMessages() {
        assertThat(SourceConnectionFailure.reason(new SQLException("secret","42000",1049))).isEqualTo("数据库名称不存在");
        assertThat(SourceConnectionFailure.reason(new SQLException("secret",new ConnectException()))).contains("端口无法连接");
        assertThat(SourceConnectionFailure.reason(new SQLException("secret",new SocketTimeoutException()))).isEqualTo("网络连接超时");
        assertThat(SourceConnectionFailure.reason(new SQLException("secret",new UnknownHostException()))).contains("无法解析");
    }
}
