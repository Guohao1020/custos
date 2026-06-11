package io.custos.broker;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.authz.AbacPdp;
import io.custos.authz.AbacPolicy;
import io.custos.authz.CasbinPdp;
import io.custos.authz.DenyApprovalHook;
import io.custos.engine.approval.ApprovalStore;
import io.custos.engine.approval.JimmerApprovalStore;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.resource.ResourceRecord;
import io.custos.engine.resource.ResourceStore;
import io.custos.engine.resource.RoleDef;
import io.custos.engine.resource.RoleKind;
import io.custos.engine.secrets.SecretsEngineRegistry;
import io.custos.engine.storage.JimmerStorage;
import io.custos.identity.*;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BrokerServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private TokenService tokens;
    private KeyPair signKey;

    /** 动态只读账号仅有 appdb 权限，连接默认库须指向 appdb（保留连接参数，仅替换库名段）。 */
    private static String appdbUrl() {
        return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1");
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
             Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_approval (id VARCHAR(160) PRIMARY KEY, agent VARCHAR(512) NOT NULL, tool VARCHAR(256) NOT NULL, resource VARCHAR(256) NOT NULL, role VARCHAR(128) NOT NULL, risk INT NOT NULL, reason VARCHAR(512), status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, decided_at BIGINT NOT NULL DEFAULT 0, expire_at BIGINT NOT NULL DEFAULT 0)");
            // 容器在同类多个测试方法间复用，清空状态表使每个方法从干净起点开始。
            st.execute("TRUNCATE TABLE custos_storage");
            st.execute("TRUNCATE TABLE custos_lease");
            st.execute("TRUNCATE TABLE custos_approval");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("TRUNCATE TABLE appdb.orders");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        signKey = g.generateKeyPair();
        tokens = new JwtTokenService(signKey, "custos", new InMemoryBlacklist());
    }

    private final CapturingAudit audit = new CapturingAudit();

    /** 捕获式审计：断言每次决策都落了一条审计行。query/count/decisionCounts 不被本 IT 触达。 */
    static final class CapturingAudit implements io.custos.engine.audit.AuditLog {
        final java.util.List<io.custos.engine.audit.AuditRecord> records = new java.util.ArrayList<>();
        public void append(io.custos.engine.audit.AuditRecord r) { records.add(r); }
        public io.custos.engine.audit.VerifyResult verify() { return io.custos.engine.audit.VerifyResult.passed(); }
        public java.util.List<io.custos.engine.audit.AuditEntry> query(io.custos.engine.audit.AuditQuery q) { return java.util.List.of(); }
        public long count(io.custos.engine.audit.AuditQuery q) { return records.size(); }
        public java.util.Map<String, Long> decisionCounts(int recentWindow) { return java.util.Map.of(); }
    }

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    /** 审批单存储：approval-flow IT 在 broker 与断言间共享同一实例（broker() 各次调用都复用）。 */
    private ApprovalStore approvalStore;

    /** 装配资源 + 审批存储（每个测试方法都从干净表起步，见 @BeforeEach 的 TRUNCATE）。 */
    private ResourceManager wireResources() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser("root");
        ds.setPassword("root");
        JSqlClient sql = JimmerClients.of(ds);
        approvalStore = new JimmerApprovalStore(sql);
        ResourceStore store = new ResourceStore(new JimmerStorage(sql, barrier()));
        DefaultLeaseManager leases = new DefaultLeaseManager(sql);
        SecretsEngineRegistry registry = new SecretsEngineRegistry();
        // ResourceManager 内部不接审计（资源生命周期审计由 engine 覆盖）；broker 决策审计走下面的 CapturingAudit。
        ResourceManager resources = new ResourceManager(store, registry, leases, null);
        // 注册一条 mysql 资源 appdb：admin root/root，jdbcUrl 指向容器 appdb，read-only 角色 schema=appdb。
        resources.register(new ResourceRecord("appdb", "db.relational", "mysql", appdbUrl(), "root", "root",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb"))));
        return resources;
    }

    private CasbinPdp readerPdp() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("""
                p, role:reader, default, tool:db/*, read, allow
                g, agent:claude-prod, role:reader, default
                """);
        return pdp;
    }

    /** 纯 RBAC：低风险一律 ALLOW（覆盖 allow / deny 基线）。 */
    private BrokerService broker() {
        ResourceManager resources = wireResources();
        return new BrokerService(tokens, readerPdp(), resources, new SecretlessQueryExecutor(), audit, approvalStore);
    }

    /**
     * ABAC 变体：RBAC 放行后用固定 55 分打分，approvalThreshold=50/denyThreshold=90 → 落入
     * [50,90) 中风险区间，DenyApprovalHook 不自动放行 → REQUIRE_APPROVAL（触发审批闭环）。
     */
    private BrokerService brokerRequiringApproval() {
        ResourceManager resources = wireResources();
        AbacPdp pdp = new AbacPdp(readerPdp(), req -> 55, new DenyApprovalHook(), new AbacPolicy(50, 90, 0, 24));
        return new BrokerService(tokens, pdp, resources, new SecretlessQueryExecutor(), audit, approvalStore);
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

    @Test
    void approvalFlowPendingThenApprovedRetryReleasesThenConsumedBlocksReplay() {
        BrokerService broker = brokerRequiringApproval();

        // 1) 中风险首发 → PENDING + approvalId（不签发凭证，不出数据）。
        QueryResult p = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"),
                tokenFor("claude-prod"));
        assertEquals(QueryStatus.PENDING, p.status());
        assertNotNull(p.approvalId());
        assertTrue(p.rows().isEmpty(), "PENDING 不得返回数据");

        // 2) 人工批准（有效窗 10 分钟）→ 带 approvalId 重发 → 放行并返回真实行数。
        approvalStore.approve(p.approvalId(), System.currentTimeMillis() + 600_000);
        QueryResult a = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only",
                        "SELECT COUNT(*) AS n FROM appdb.orders", p.approvalId()),
                tokenFor("claude-prod"));
        assertTrue(a.allowed());
        assertEquals(2L, ((Number) a.rows().get(0).get("n")).longValue());

        // 3) 同一 approvalId 二次重发 → 已 CONSUMED → 拒绝（单次防重放）。
        QueryResult again = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only", "SELECT 1", p.approvalId()),
                tokenFor("claude-prod"));
        assertFalse(again.allowed());
    }

    @Test
    void deniedApprovalIdReplayIsRejected() {
        BrokerService broker = brokerRequiringApproval();

        // 直接造一条审批单并 deny：带其 id 重发应被拒（非 APPROVED 状态）。
        String id = approvalStore.create("agent:claude-prod", "db/query_orders", "appdb", "read-only", 55, "test");
        approvalStore.deny(id);

        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only", "SELECT 1", id),
                tokenFor("claude-prod"));
        assertFalse(r.allowed());
        assertEquals(QueryStatus.DENIED, r.status());
    }
}
