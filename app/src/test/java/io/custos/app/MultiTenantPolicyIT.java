package io.custos.app;

import io.custos.app.operator.OperatorService;
import io.custos.app.policy.PolicyService;
import io.custos.app.security.AdminTokenFilter;
import io.custos.identity.AgentId;
import io.custos.identity.TokenService;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M18 多租户 PDP 路由 + /cluster 端到端（真端口 + Testcontainers MySQL）。
 *
 * <p>默认单租户配置（InMemory 控制面，server-addr 空 → NoOpServiceRegistry + 单租户 default）下验证：
 * ① {@code GET /cluster/peers} 无 token → 401（AdminTokenFilter 门控，先于 controller）；
 *    带 admin token → 200 JSON 数组（NoOp 注册器，WebServerInitializedEvent 已触发 self 注册）。
 * ② 经一次真实 query_db：tenant 缺省（"default"）→ 命中默认租户策略 ALLOWED；
 *    带 tenant="ghost"（未配置）→ 路由到 denyAll → DENIED（隔离闸：未配置租户一律拒）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(MultiTenantPolicyIT.FixedTokenConfig.class)
class MultiTenantPolicyIT {

    static final String ADMIN_TOKEN = "it-admin-token";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    static String appdbUrl() { return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1"); }

    /** 覆盖 HostConfig 的 adminTokenFilter（它读 env），注入固定 token 并门控 /cluster，使鉴权可被真实断言。 */
    @TestConfiguration
    static class FixedTokenConfig {
        @Bean
        FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
            FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(ADMIN_TOKEN));
            reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*",
                    "/resources/*", "/approvals/*", "/leases/*", "/monitor/*", "/cluster/*");
            return reg;
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("custos.engine.storage-url", MYSQL::getJdbcUrl);
        r.add("custos.engine.storage-username", () -> "root");
        r.add("custos.engine.storage-password", () -> "root");
        r.add("custos.nacos.server-addr", () -> "");   // 空 → InMemoryControlPlane + NoOpServiceRegistry + 单租户 default
    }

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_audit (seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL, task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128), prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_approval (id VARCHAR(160) PRIMARY KEY, agent VARCHAR(512) NOT NULL, tool VARCHAR(256) NOT NULL, resource VARCHAR(256) NOT NULL, role VARCHAR(128) NOT NULL, risk INT NOT NULL, reason VARCHAR(512), status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, decided_at BIGINT NOT NULL DEFAULT 0, expire_at BIGINT NOT NULL DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
        }
    }

    @LocalServerPort int port;
    @Autowired OperatorService op;
    @Autowired PolicyService policy;
    @Autowired TokenService tokens;

    /** 永不抛异常的 RestTemplate：4xx/5xx 也回 ResponseEntity 以便断言状态码；JdkClient 避免带 body 401 触发重试。 */
    private RestTemplate http() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
            @Override public void handleError(ClientHttpResponse response) { }
        });
        return rt;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> get(RestTemplate http, String path, String token) {
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
    }

    @Test
    void clusterPeersGatedAndTenantRoutingIsolates() {
        RestTemplate http = http();

        // ① /cluster/peers 无 token → 401（filter 先于 controller）
        assertEquals(HttpStatus.UNAUTHORIZED, get(http, "/cluster/peers", null).getStatusCode());

        // 带 admin token → 200 JSON 数组（NoOp 注册器；RANDOM_PORT 下 self 注册已在 WebServerInitializedEvent 触发）
        ResponseEntity<String> peers = get(http, "/cluster/peers", ADMIN_TOKEN);
        assertEquals(HttpStatus.OK, peers.getStatusCode(), peers.getBody());
        assertTrue(peers.getBody().trim().startsWith("["), peers.getBody());

        // 解封
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1)); op.unseal(shares.get(2));
        assertFalse(op.status().sealed());

        // 注册 appdb 资源 + 写默认租户策略（dom=default；CasbinPdp 模型需 RBAC grouping 绑定 agent→role）
        Map<String, Object> resourceBody = Map.of(
                "name", "appdb", "type", "db.relational", "dialect", "mysql",
                "jdbcUrl", appdbUrl(), "adminUsername", "root", "adminPassword", "root",
                "roles", List.of(Map.of(
                        "name", "read-only", "kind", "BUILTIN_READONLY",
                        "creationStatements", List.of(), "revocationStatements", List.of(),
                        "defaultTtlSeconds", 3600, "schema", "appdb")));
        ResponseEntity<String> reg = http.postForEntity(url("/resources"),
                new HttpEntity<>(resourceBody, withAuth(jsonHeaders(), ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, reg.getStatusCode(), reg.getBody());

        policy.put("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default\n");

        String userTok = tokens.issue(new AgentId("corp.example", "claude-prod", "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();

        // ② tenant 缺省（"default"）→ 命中默认租户策略 → ALLOWED
        Map<String, String> defaultTenant = Map.of(
                "tool", "db/query_orders", "resource", "appdb",
                "sql", "SELECT COUNT(*) AS n FROM appdb.orders", "userToken", userTok);
        ResponseEntity<String> okQ = http.postForEntity(url("/query_db"),
                new HttpEntity<>(defaultTenant, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, okQ.getStatusCode(), okQ.getBody());
        assertTrue(okQ.getBody().contains("\"status\":\"ALLOWED\""), okQ.getBody());

        // ③ tenant="ghost"（未配置）→ 路由到 denyAll → DENIED（隔离闸）
        Map<String, String> ghostTenant = Map.of(
                "tool", "db/query_orders", "resource", "appdb",
                "sql", "SELECT COUNT(*) AS n FROM appdb.orders", "userToken", userTok,
                "tenant", "ghost");
        ResponseEntity<String> denyQ = http.postForEntity(url("/query_db"),
                new HttpEntity<>(ghostTenant, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, denyQ.getStatusCode(), denyQ.getBody());
        assertTrue(denyQ.getBody().contains("\"status\":\"DENIED\""), denyQ.getBody());
    }

    private static HttpHeaders withAuth(HttpHeaders h, String token) {
        if (token != null) h.setBearerAuth(token);
        return h;
    }
}
