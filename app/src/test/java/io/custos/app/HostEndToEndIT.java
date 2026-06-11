package io.custos.app;

import io.custos.app.operator.OperatorService;
import io.custos.app.policy.PolicyService;
import io.custos.broker.QueryIntent;
import io.custos.broker.QueryResult;
import io.custos.engine.resource.ResourceRecord;
import io.custos.engine.resource.RoleDef;
import io.custos.engine.resource.RoleKind;
import io.custos.identity.AgentId;
import io.custos.identity.TokenService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HostEndToEndIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    static String appdbUrl() { return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1"); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("custos.engine.storage-url", MYSQL::getJdbcUrl);
        r.add("custos.engine.storage-username", () -> "root");
        r.add("custos.engine.storage-password", () -> "root");
        r.add("custos.nacos.server-addr", () -> "");   // 空 → InMemoryControlPlane
    }

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_audit (seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL, task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128), prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
        }
    }

    @Autowired OperatorService op;
    @Autowired PolicyService policy;
    @Autowired TokenService tokens;

    @Test
    void sealedThenUnsealThenPolicyThenQueryAllowAndDenyAndAudit() {
        // AC1：启动 sealed，未解封运营组件不可用
        assertTrue(op.status().sealed());
        assertThrows(IllegalStateException.class, () -> op.unsealed());

        // 解封
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1));
        assertFalse(op.unseal(shares.get(2)).sealed());

        // 运行期注册目标库资源 appdb（高权限凭证经 Barrier 加密落盘，不再硬编码）
        RoleDef readOnly = new RoleDef("read-only", RoleKind.BUILTIN_READONLY,
                Collections.emptyList(), Collections.emptyList(), 3600, "appdb");
        op.unsealed().resourceManager().register(new ResourceRecord(
                "appdb", "db.relational", "mysql", appdbUrl(), "root", "root", List.of(readOnly)));

        // 写策略（允许 claude-prod 只读）
        policy.put("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default\n");

        // 准：claude-prod 查询返回行，且 secretless（resource=appdb）
        String allowTok = tokens.issue(new AgentId("corp.example", "claude-prod", "s1"), Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
        QueryResult ok = op.unsealed().broker().queryDb(new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"), allowTok);
        assertTrue(ok.allowed());
        assertEquals(3L, ((Number) ok.rows().get(0).get("n")).longValue());
        assertFalse(ok.toString().contains("v_ro_"));

        // 拒：evil-agent
        String denyTok = tokens.issue(new AgentId("corp.example", "evil-agent", "s1"), Set.of("x"), "broker", Duration.ofMinutes(15)).jwt();
        QueryResult denied = op.unsealed().broker().queryDb(new QueryIntent("db/query_orders", "appdb", "SELECT 1"), denyTok);
        assertFalse(denied.allowed());

        // 审计可校验（空链/有链均应 ok）
        assertTrue(op.unsealed().audit().verify().ok());
    }
}
