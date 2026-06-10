package io.custos.broker;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.authz.CasbinPdp;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.secrets.DynamicDbCredentials;
import io.custos.identity.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BrokerServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private Connection admin;
    private TokenService tokens;
    private KeyPair signKey;

    /** 动态只读账号仅有 appdb 权限，连接默认库须指向 appdb（保留 getJdbcUrl 参数）。 */
    private static String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1");
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        signKey = g.generateKeyPair();
        tokens = new JwtTokenService(signKey, "custos", new InMemoryBlacklist());
    }

    private final CapturingAudit audit = new CapturingAudit();

    /** 捕获式审计：断言每次决策都落了一条审计行。 */
    static final class CapturingAudit implements io.custos.engine.audit.AuditLog {
        final java.util.List<io.custos.engine.audit.AuditRecord> records = new java.util.ArrayList<>();
        public void append(io.custos.engine.audit.AuditRecord r) { records.add(r); }
        public io.custos.engine.audit.VerifyResult verify() { return io.custos.engine.audit.VerifyResult.passed(); }
    }

    private BrokerService broker() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("""
                p, role:reader, default, tool:db/*, read, allow
                g, agent:claude-prod, role:reader, default
                """);
        // 租约表在 root 的默认库 test 中创建；LeaseManager 的 JSqlClient 也指向 test（root 可访问）。
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser("root");
        ds.setPassword("root");
        DefaultLeaseManager leases = new DefaultLeaseManager(JimmerClients.of(ds));
        DynamicDbCredentials creds = new DynamicDbCredentials(admin, leases, appdbUrl());
        return new BrokerService(tokens, pdp, creds, new SecretlessQueryExecutor(), appdbUrl(), audit);
    }

    private String tokenFor(String agent) {
        return tokens.issue(new AgentId("corp.example", agent, "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
    }

    @Test
    void allowedQueryReturnsRowsAndNeverLeaksCredentials() {
        QueryResult r = broker().queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"),
                tokenFor("claude-prod"));
        assertTrue(r.allowed());
        assertEquals(2L, ((Number) r.rows().get(0).get("n")).longValue());
        // secretless 断言：整个结果序列化里不得出现任何用户名/密码痕迹
        String dump = r.toString();
        assertFalse(dump.contains("v_ro_"), "结果不得含动态用户名");
        assertFalse(dump.toLowerCase().contains("password"), "结果不得含密码字段");
        // 审计接线：允许的查询也必须落一条 allow 审计行
        assertEquals(1, audit.records.size());
        assertEquals("allow", audit.records.get(0).decision());
        assertEquals("agent:claude-prod", audit.records.get(0).actor());
    }

    @Test
    void deniedAgentGetsReasonAndNoData() {
        QueryResult r = broker().queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                tokenFor("evil-agent"));    // 不在策略里
        assertFalse(r.allowed());
        assertNotNull(r.denyReason());
        assertTrue(r.rows().isEmpty());
    }
}
