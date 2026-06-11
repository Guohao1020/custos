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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 BrokerService 在各放行/拒绝/审批路径上对 BrokerMetrics 的埋点。复用 BrokerServiceIT 的容器装配
 * (appdb/orders 2 行、reader 策略、中风险审批变体),注入一个 capturing fake 累计各 decision/action 次数
 * 与计时调用次数。只断言计数,不改任何决策语义。
 */
@Testcontainers
class BrokerMetricsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private TokenService tokens;
    private KeyPair signKey;
    private ApprovalStore approvalStore;
    private final Capturing metrics = new Capturing();

    /** 累计各 decision/action 计数 + 各计时调用次数。线程安全(虽然本测试单线程)。 */
    static final class Capturing implements BrokerMetrics {
        final Map<String, Integer> decisions = new ConcurrentHashMap<>();
        final Map<String, Integer> approvals = new ConcurrentHashMap<>();
        int issued, revoked, queryTimed, pdpTimed, issueTimed, revokeTimed;

        public void recordDecision(String d) { decisions.merge(d, 1, Integer::sum); }
        public void recordApproval(String a) { approvals.merge(a, 1, Integer::sum); }
        public void recordCredentialIssued() { issued++; }
        public void recordCredentialRevoked() { revoked++; }
        public void recordQueryDuration(Duration d) { queryTimed++; }
        public void recordPdpDecisionDuration(Duration d) { pdpTimed++; }
        public void recordCredentialIssueDuration(Duration d) { issueTimed++; }
        public void recordCredentialRevokeDuration(Duration d) { revokeTimed++; }

        int decision(String k) { return decisions.getOrDefault(k, 0); }
        int approval(String k) { return approvals.getOrDefault(k, 0); }
    }

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

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

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
        ResourceManager resources = new ResourceManager(store, registry, leases, null);
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

    /** 纯 RBAC：低风险一律 ALLOW（覆盖 allow / deny 基线），注入 capturing metrics。 */
    private BrokerService broker() {
        ResourceManager resources = wireResources();
        return new BrokerService(tokens, readerPdp(), resources, new SecretlessQueryExecutor(), null, approvalStore, metrics);
    }

    /** ABAC 变体：固定 55 分落入 [50,90) 中风险区间 → REQUIRE_APPROVAL（触发审批闭环），注入 capturing metrics。 */
    private BrokerService brokerRequiringApproval() {
        ResourceManager resources = wireResources();
        AbacPdp pdp = new AbacPdp(readerPdp(), req -> 55, new DenyApprovalHook(), new AbacPolicy(50, 90, 0, 24));
        return new BrokerService(tokens, pdp, resources, new SecretlessQueryExecutor(), null, approvalStore, metrics);
    }

    private String tokenFor(String agent) {
        return tokens.issue(new AgentId("corp.example", agent, "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
    }

    @Test
    void allowPathRecordsDecisionAndCredentialAndDurations() {
        QueryResult r = broker().queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"),
                tokenFor("claude-prod"));
        assertTrue(r.allowed());

        assertEquals(1, metrics.decision("allow"), "放行一次 → allow 计数 1");
        assertEquals(1, metrics.issued, "签发一次凭证");
        assertEquals(1, metrics.revoked, "撤销一次凭证（即用即焚）");
        assertTrue(metrics.pdpTimed >= 1, "PDP 决策计时至少一次");
        assertTrue(metrics.issueTimed >= 1, "凭证签发计时至少一次");
        assertTrue(metrics.queryTimed >= 1, "查询执行计时至少一次");
        assertTrue(metrics.revokeTimed >= 1, "凭证撤销计时至少一次");
    }

    @Test
    void denyPathRecordsDecisionAndNoCredential() {
        QueryResult r = broker().queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                tokenFor("evil-agent"));   // 不在策略里 → DENY
        assertFalse(r.allowed());

        assertEquals(1, metrics.decision("deny"), "拒绝一次 → deny 计数 1");
        assertEquals(0, metrics.issued, "拒绝不签发凭证");
        assertEquals(0, metrics.revoked, "拒绝不撤销凭证");
        assertTrue(metrics.pdpTimed >= 1, "拒绝路径仍经过 PDP 计时");
    }

    @Test
    void approvalFlowRecordsRequireApprovalCreatedThenAllowApprovedConsumed() {
        BrokerService broker = brokerRequiringApproval();

        // 1) 中风险首发 → PENDING：require-approval 决策 + approval created。
        QueryResult p = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"),
                tokenFor("claude-prod"));
        assertEquals(QueryStatus.PENDING, p.status());
        assertNotNull(p.approvalId());
        assertEquals(1, metrics.decision("require-approval"), "首发 → require-approval 决策 1");
        assertEquals(1, metrics.approval("created"), "首发 → approval created 1");
        assertEquals(0, metrics.issued, "PENDING 不签发凭证");

        // 2) 人工批准 → 带 id 重发 → 放行：allow-approved 决策 + approval consumed + 凭证签发/撤销。
        approvalStore.approve(p.approvalId(), System.currentTimeMillis() + 600_000);
        QueryResult a = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only",
                        "SELECT COUNT(*) AS n FROM appdb.orders", p.approvalId()),
                tokenFor("claude-prod"));
        assertTrue(a.allowed());
        assertEquals(1, metrics.decision("allow-approved"), "重发放行 → allow-approved 决策 1");
        assertEquals(1, metrics.approval("consumed"), "重发放行 → approval consumed 1");
        assertEquals(1, metrics.issued, "放行签发一次凭证");
        assertEquals(1, metrics.revoked, "放行撤销一次凭证");
        // PDP 仅在首发评估一次；审批重发路径直接走 issueAndRun 不再评估 PDP，故全程 pdpTimed 恒为 1。
        assertEquals(1, metrics.pdpTimed, "PDP 仅首发评估一次，审批重发不再评估");
    }

    @Test
    void deniedApprovalIdReplayRecordsDeny() {
        BrokerService broker = brokerRequiringApproval();

        String id = approvalStore.create("agent:claude-prod", "db/query_orders", "appdb", "read-only", 55, "test");
        approvalStore.deny(id);   // 非 APPROVED 状态 → 重发应被拒并计 deny

        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only", "SELECT 1", id),
                tokenFor("claude-prod"));
        assertFalse(r.allowed());
        assertEquals(QueryStatus.DENIED, r.status());
        assertEquals(1, metrics.decision("deny"), "审批无效重发 → deny 计数 1");
        assertEquals(0, metrics.issued, "无效审批不签发凭证");
    }
}
