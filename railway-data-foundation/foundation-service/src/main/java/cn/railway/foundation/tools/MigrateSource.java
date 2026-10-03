package cn.railway.foundation.tools;

import org.flywaydb.core.Flyway;

/** Standalone infrastructure command, never invoked by the Foundation web service. */
public final class MigrateSource {
    private MigrateSource() {}

    public static void main(String[] args) {
        Flyway.configure().dataSource(required("SOURCE_MIGRATION_URL"),
                        required("SOURCE_DB_USER"), required("SOURCE_DB_PASSWORD"))
                .locations("filesystem:" + required("SOURCE_MIGRATION_PATH"))
                .cleanDisabled(true).load().migrate();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + key);
        return value;
    }
}
