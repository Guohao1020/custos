package io.custos.engine.resource;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresAdapterIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    Connection admin;
    @BeforeEach void setUp() throws Exception {
        admin = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS orders (id INT)");
            st.execute("INSERT INTO orders VALUES (1),(2)");
        }
    }
    private RoleDef readOnly() { return new RoleDef("read-only", RoleKind.BUILTIN_READONLY, java.util.List.of(), java.util.List.of(), 3600, "public"); }

    @Test void issueThenRevoke() throws Exception {
        PostgresAdapter a = new PostgresAdapter();
        MintedCred c = a.issue(admin, readOnly(), Duration.ofMinutes(5));
        assertTrue(c.username().startsWith("v_ro_"));
        try (Connection u = DriverManager.getConnection(PG.getJdbcUrl(), c.username(), c.password());
             ResultSet rs = u.createStatement().executeQuery("SELECT COUNT(*) FROM orders")) {
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1));
        }
        a.revoke(admin, c.username(), readOnly());
        try (ResultSet rs = admin.createStatement().executeQuery(
                "SELECT COUNT(*) FROM pg_roles WHERE rolname='" + c.username() + "'")) {
            assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
        }
    }
}
