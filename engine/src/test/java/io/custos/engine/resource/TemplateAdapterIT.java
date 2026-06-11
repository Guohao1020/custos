package io.custos.engine.resource;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TemplateAdapterIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");
    Connection admin;
    @BeforeEach void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
    }
    private RoleDef tpl() {
        return new RoleDef("ro-tpl", RoleKind.TEMPLATE,
            java.util.List.of(
                "CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'",
                "GRANT SELECT ON `appdb`.* TO '{{name}}'@'%'",
                "FLUSH PRIVILEGES"),
            java.util.List.of("DROP USER IF EXISTS '{{name}}'@'%'", "FLUSH PRIVILEGES"),
            3600, "appdb");
    }

    @Test void issueThenRevoke() throws Exception {
        TemplateAdapter a = new TemplateAdapter();
        MintedCred c = a.issue(admin, tpl(), Duration.ofMinutes(5));
        assertTrue(c.username().startsWith("v_ro_"));
        String url = MYSQL.getJdbcUrl().replaceFirst("/\\w+(\\?|$)", "/appdb$1");
        try (Connection u = DriverManager.getConnection(url, c.username(), c.password());
             ResultSet rs = u.createStatement().executeQuery("SELECT COUNT(*) FROM appdb.orders")) {
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1));
        }
        a.revoke(admin, c.username(), tpl());
        try (ResultSet rs = admin.createStatement().executeQuery(
                "SELECT COUNT(*) FROM mysql.user WHERE user='" + c.username() + "'")) {
            assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
        }
    }
}
