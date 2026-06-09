package io.custos.engine.audit;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class HashChainAuditLogIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private MysqlDataSource ds;
    private HashChainAuditLog audit;

    @BeforeEach
    void setUp() throws Exception {
        ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_audit (
                seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
                task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32),
                result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
                prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)""");
        }
        JSqlClient sql = JimmerClients.of(ds);
        audit = new HashChainAuditLog(sql, new IntlSuite(), new byte[32]);
    }

    private AuditRecord rec(String actor, String action) {
        return new AuditRecord(System.currentTimeMillis(), actor, "task", "db:orders", action, "ALLOW", "digest", "pwd=xxx");
    }

    @Test
    void appendThenVerifyPasses() {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentB", "read"));
        assertTrue(audit.verify().ok());
    }

    @Test
    void tamperingBreaksChain() throws Exception {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentA", "read"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE custos_audit SET action='write' WHERE seq=1");
        }
        var r = audit.verify();
        assertFalse(r.ok());
        assertEquals(1L, r.brokenAtSeq());
    }

    @Test
    void sensitiveFieldHmacNotPlaintext() throws Exception {
        audit.append(rec("agentA", "read"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT sensitive_hmac FROM custos_audit WHERE seq=1")) {
            assertTrue(rs.next());
            assertNotEquals("pwd=xxx", rs.getString(1));
        }
    }
}
