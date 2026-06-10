package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresDynamicCredentialsIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    /** 内存租约替身（同 AkSkSecretsEngineTest 模式，聚焦凭证逻辑；真实租约 DB 行为由 M02 IT 覆盖）。 */
    static final class FakeLeases implements LeaseManager {
        final Map<String, Revoker> revokers = new HashMap<>();
        int seq = 0;

        public Lease register(String p, Duration ttl, Revoker r) {
            String id = p + "/" + (seq++);
            revokers.put(id, r);
            long now = System.currentTimeMillis();
            return new Lease(id, p, now, now + ttl.toMillis());
        }

        public Lease renew(String id, Duration inc) { throw new UnsupportedOperationException(); }

        public void revoke(String id) {
            Revoker r = revokers.remove(id);
            if (r != null) r.revoke(null);
        }

        public int revokePrefix(String p) { return 0; }
    }

    private Connection admin;
    private PostgresDynamicCredentials creds;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS orders (id INT)");
            st.execute("INSERT INTO orders VALUES (1),(2)");
        }
        creds = new PostgresDynamicCredentials(admin, new FakeLeases());
    }

    @Test
    void issuedRoleReadsButCannotWrite() throws Exception {
        IssuedCred c = creds.issue("public", Duration.ofHours(1));
        try (Connection user = DriverManager.getConnection(PG.getJdbcUrl(), c.username(), c.password());
             Statement st = user.createStatement()) {
            assertTrue(st.executeQuery("SELECT COUNT(*) FROM orders").next());
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO orders VALUES (3)"));
        }
    }

    @Test
    void revokeDropsRole() throws Exception {
        IssuedCred c = creds.issue("public", Duration.ofHours(1));
        creds.revoke(c.leaseId());
        assertThrows(SQLException.class,
                () -> DriverManager.getConnection(PG.getJdbcUrl(), c.username(), c.password()));
    }
}
