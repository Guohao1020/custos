package io.custos.broker;

import io.custos.engine.secrets.IssuedCred;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SecretlessQueryExecutorIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
            st.execute("CREATE USER IF NOT EXISTS 'ro_user'@'%' IDENTIFIED BY 'ro_pwd'");
            st.execute("GRANT SELECT ON appdb.* TO 'ro_user'@'%'");
            st.execute("FLUSH PRIVILEGES");
        }
    }

    /** ro_user 仅有 appdb 权限，连接默认库须指向 appdb（保留 getJdbcUrl 参数）。 */
    private static String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1");
    }

    private final SecretlessQueryExecutor exec = new SecretlessQueryExecutor();

    @Test
    void runsReadonlyQueryAndReturnsRows() {
        IssuedCred cred = new IssuedCred("ro_user", "ro_pwd", "lease-1", Long.MAX_VALUE);
        List<Map<String, Object>> rows = exec.runReadonly(appdbUrl(), cred, "SELECT COUNT(*) AS n FROM appdb.orders");
        assertEquals(1, rows.size());
        assertEquals(3L, ((Number) rows.get(0).get("n")).longValue());
    }

    @Test
    void rejectsNonSelectStatement() {
        IssuedCred cred = new IssuedCred("ro_user", "ro_pwd", "lease-1", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> exec.runReadonly(appdbUrl(), cred, "DELETE FROM appdb.orders"));
        assertThrows(IllegalArgumentException.class,
                () -> exec.runReadonly(appdbUrl(), cred, "SELECT 1; DROP TABLE appdb.orders"));
    }
}
