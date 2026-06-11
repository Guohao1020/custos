package io.custos.app;

import io.custos.app.operator.OperatorService;
import io.custos.app.security.AdminTokenFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /approvals REST 端到端（真端口 + Testcontainers MySQL）：
 * 鉴权（无 token→401）、解封后列表、注入待批后 approve 即移出 pending。
 * 审批单非密钥、明文直连 DB（不经 Barrier），此处只验 REST + store 闭环；
 * approve 后携 approvalId 放行的端到端逻辑由 broker 模块的 BrokerServiceIT 覆盖。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(ApprovalFlowIT.FixedTokenConfig.class)
class ApprovalFlowIT {

    static final String ADMIN_TOKEN = "it-admin-token";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    /** 覆盖 HostConfig 的 adminTokenFilter（它读 env），注入固定 token，使鉴权可被真实断言。 */
    @TestConfiguration
    static class FixedTokenConfig {
        @Bean
        FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
            FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(ADMIN_TOKEN));
            reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*", "/resources/*", "/approvals/*");
            return reg;
        }
    }

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
            st.execute("CREATE TABLE IF NOT EXISTS custos_approval (id VARCHAR(160) PRIMARY KEY, agent VARCHAR(512) NOT NULL, tool VARCHAR(256) NOT NULL, resource VARCHAR(256) NOT NULL, role VARCHAR(128) NOT NULL, risk INT NOT NULL, reason VARCHAR(512), status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, decided_at BIGINT NOT NULL DEFAULT 0, expire_at BIGINT NOT NULL DEFAULT 0)");
        }
    }

    @LocalServerPort int port;
    @Autowired OperatorService op;

    /**
     * 自建 RestTemplate：JdkClientHttpRequestFactory 避免带 body 的 POST 收到 401 时触发认证重试；
     * 永不抛异常的 errorHandler 使 4xx/5xx 也返回 ResponseEntity 以便断言状态码。
     */
    private RestTemplate http() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
            @Override public void handleError(ClientHttpResponse response) { }
        });
        return rt;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    @Test
    void unauthorizedThenListThenApproveRemovesFromPending() {
        RestTemplate http = http();

        // /approvals 无 admin token → 401（即使 sealed，filter 先于 controller）
        ResponseEntity<String> noTok = http.exchange(url("/approvals"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noTok.getStatusCode());

        // 解封
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1)); op.unseal(shares.get(2));
        assertFalse(op.status().sealed());

        // 带 admin token GET → 200，初始空 pending
        ResponseEntity<String> empty = http.exchange(url("/approvals"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertEquals("[]", empty.getBody());

        // 经 store 注入一条待批（模拟一次 REQUIRE_APPROVAL 决策的产物）
        String id = op.unsealed().approvals().create(
                "agent:claude-prod", "db/export_orders", "appdb", "read-only", 80, "high-risk export");

        // 带 admin token GET → 该单出现在 pending
        ResponseEntity<String> listed = http.exchange(url("/approvals"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertTrue(listed.getBody().contains(id), listed.getBody());
        assertTrue(listed.getBody().contains("db/export_orders"), listed.getBody());

        // approve 无 token → 401
        ResponseEntity<String> apprNoTok = http.postForEntity(url("/approvals/" + id + "/approve"),
                new HttpEntity<>(authHeaders(null)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, apprNoTok.getStatusCode());

        // 带 admin token approve → 200
        ResponseEntity<String> appr = http.postForEntity(url("/approvals/" + id + "/approve"),
                new HttpEntity<>(authHeaders(ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, appr.getStatusCode(), appr.getBody());
        assertTrue(appr.getBody().contains("approved"), appr.getBody());

        // approve 后该单不再在 pending 列表
        ResponseEntity<String> after = http.exchange(url("/approvals"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, after.getStatusCode());
        assertFalse(after.getBody().contains(id), "approve 后该审批单应移出 pending: " + after.getBody());
    }
}
