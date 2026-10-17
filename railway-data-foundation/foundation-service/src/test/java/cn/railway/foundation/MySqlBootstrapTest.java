package cn.railway.foundation;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: use ONLY dedicated disposable test databases. No clean/drop is performed. */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MySqlBootstrapTest extends AuthenticatedApiTest {
    @Autowired Flyway flyway;
    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate http;

    @Test void realMySqlFoundationStartsWithFlywayAndMyBatis() throws Exception {
        assertThat(context.getBean(MybatisPlusAutoConfiguration.class)).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("20");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            try (var r = c.createStatement().executeQuery("SHOW TABLES")) {
                var tables = new HashSet<String>();
                while (r.next()) tables.add(r.getString(1));
                assertThat(tables).containsExactlyInAnyOrder("workspace_registration", "workspace_registration_guard", "workspace_account", "flyway_schema_history", "source_system",
                        "metadata_scan_task", "metadata_scan_table", "metadata_scan_field", "data_resource", "resource_field", "resource_revision", "capability", "source_credential", "sharing_subject", "access_policy", "sharing_relationship", "sharing_relationship_event", "caller_credential", "caller_credential_event", "sharing_runtime_report", "sharing_runtime_event", "kafka_subscription_grant", "kafka_subscription_event", "kafka_subscription_execution", "kafka_consumption_report", "kafka_receiver_event");
            }
            try (var r = c.createStatement().executeQuery("SELECT COUNT(*), SUM(COLUMN_COMMENT = '') "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME <> 'flyway_schema_history'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getInt(1)).isGreaterThan(70);
                assertThat(r.getInt(2)).isZero();
            }
        }
        var response = http.getForEntity("/api/v1/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":0", "\"status\":\"UP\"");
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody()).contains("/api/v1/health");
    }

    @Test void sourceMigrationsSeedTwentyEventsAndAreRepeatable() throws Exception {
        String url = required("TEST_SOURCE_DB_URL");
        String user = required("TEST_SOURCE_DB_USER");
        String password = required("TEST_SOURCE_DB_PASSWORD");
        assertThat(url).isNotEqualTo(System.getenv("FOUNDATION_DB_URL"));
        var migrationPath = Path.of("..", "sql", "source").toAbsolutePath().normalize();
        var sourceFlyway = Flyway.configure().dataSource(url, user, password)
                .locations("filesystem:" + migrationPath).cleanDisabled(true).load();
        sourceFlyway.migrate();
        assertThat(sourceFlyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, user, password)) {
            try (var r = c.createStatement().executeQuery("SELECT COUNT(*), COUNT(DISTINCT event_type), COUNT(DISTINCT train_no), SUM(train_no='X8021') FROM container_event")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(20);
                assertThat(r.getInt(2)).isEqualTo(4);
                assertThat(r.getInt(3)).isGreaterThan(1);
                assertThat(r.getInt(4)).isGreaterThan(0);
            }
            try (var r = c.createStatement().executeQuery("SELECT COUNT(*), SUM(column_comment <> '') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='container_event'")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(12);
                assertThat(r.getInt(2)).isEqualTo(12);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test setting: " + name);
        return value;
    }
}
