package cn.railway.foundation;

import cn.railway.foundation.metadata.MysqlConnectionSettings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MysqlConfigurationTest {
    @Test void rejectsJdbcUrlAndHostParameterInjection() {
        for (String host : new String[]{"localhost?allowMultiQueries=true","localhost/other","user@localhost","::1"}) {
            assertThatThrownBy(() -> new MysqlConnectionSettings(host,3306,"port_source_db","reader","secret","DISABLED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MysqlConnectionSettings("localhost",3306,"db?x=y","reader","secret","DISABLED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidPortAndTlsModeAndDoesNotExposePassword() {
        assertThatThrownBy(() -> new MysqlConnectionSettings("localhost",0,"db","reader","secret","DISABLED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MysqlConnectionSettings("localhost",3306,"db","reader","secret","arbitrary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MysqlConnectionSettings("localhost",3306,"db","reader","secret","REQUIRED").toString()).doesNotContain("secret");
    }
}
