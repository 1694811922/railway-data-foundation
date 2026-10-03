package cn.railway.foundation.metadata;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import cn.railway.foundation.source.SourceEntity;

public final class MysqlMetadataAdapter implements MetadataAdapter {
    private final SourceEntity source;
    private final String database;
    private final SourceConnectionFactory connections;
    public MysqlMetadataAdapter(SourceEntity source, String database, SourceConnectionFactory connections) {
        this.source=source; this.database=database; this.connections=connections;
    }

    private Connection connect() throws SQLException {
        return connections.openConnection(source);
    }

    @Override public ConnectionTest probeConnection() {
        long started = System.nanoTime();
        try (var connection = connect()) {
            var metadata = connection.getMetaData();
            boolean valid = connection.isValid(3);
            return new ConnectionTest(valid, metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
                    elapsed(started), valid ? "连接成功" : "连接校验失败",valid ? null : "SOURCE_CONNECTION_FAILED");
        } catch (SQLException ex) {
            // Raw driver exceptions may reveal host/user details. Never return them to clients.
            return new ConnectionTest(false, null, null, elapsed(started),SourceConnectionFailure.reason(ex),"SOURCE_CONNECTION_FAILED");
        }
    }

    @Override public List<TableMetadata> scanTables() {
        String sql = "SELECT TABLE_SCHEMA,TABLE_NAME,TABLE_COMMENT,TABLE_ROWS FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history' ORDER BY TABLE_NAME";
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                var tables = new ArrayList<TableMetadata>();
                while (rows.next()) tables.add(new TableMetadata(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getObject(4) == null ? null : rows.getLong(4)));
                return List.copyOf(tables);
            }
        } catch (SQLException ex) { throw scanFailure(ex); }
    }

    @Override public List<FieldMetadata> scanFields(String schemaName, String tableName) {
        if (!database.equals(schemaName) || tableName == null || tableName.isBlank() || tableName.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "只能扫描当前数据源中的表");
        }
        String sql = "SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,COLUMN_DEFAULT,COLUMN_COMMENT,ORDINAL_POSITION "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                var fields = new ArrayList<FieldMetadata>();
                while (rows.next()) fields.add(new FieldMetadata(rows.getString(1), rows.getString(2), rows.getString(3),
                        "YES".equals(rows.getString(4)), "PRI".equals(rows.getString(5)), rows.getString(6), rows.getString(7), rows.getInt(8)));
                return List.copyOf(fields);
            }
        } catch (SQLException ex) { throw scanFailure(ex); }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static BusinessException scanFailure(SQLException ex) {
        return new BusinessException(ErrorCode.BUSINESS_ERROR,"元数据读取失败："+SourceConnectionFailure.reason(ex));
    }
}
