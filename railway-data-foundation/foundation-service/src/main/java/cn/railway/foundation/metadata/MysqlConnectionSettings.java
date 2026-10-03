package cn.railway.foundation.metadata;

/** No generated toString: a connection configuration contains a password. */
public final class MysqlConnectionSettings {
    final String host;
    final int port;
    final String database;
    final String username;
    final String password;
    final String sslMode;

    public MysqlConnectionSettings(String host, int port, String database, String username, String password, String sslMode) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || port < 1 || port > 65535
                || database == null || !database.matches("[A-Za-z0-9_]{1,64}")
                || username == null || username.isBlank() || username.length() > 128 || password == null
                || sslMode == null || !java.util.Set.of("DISABLED","PREFERRED","REQUIRED","VERIFY_CA","VERIFY_IDENTITY").contains(sslMode)) {
            throw new IllegalArgumentException("Invalid MySQL connection configuration");
        }
        this.host=host; this.port=port; this.database=database;
        this.username=username; this.password=password; this.sslMode=sslMode;
    }
}
