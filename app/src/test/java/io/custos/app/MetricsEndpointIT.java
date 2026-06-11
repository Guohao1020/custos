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
 * M17 可观测性端点端到端（真端口 + Testcontainers MySQL）：
 * ① 无 token GET /actuator/prometheus → 401（admin-gated）；
 * ② GET /actuator/health 无 token → 200 含 "UP"（开放探活，不门控）；
 * ③ 带 admin token GET /actuator/prometheus → 200 含 custos_seal_sealed（gauge 已注册）；
 * ④ 经一次真实 query_db 后，prometheus 暴露 custos_decisions_total（broker 埋点经 MicrometerBrokerMetrics 落地）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(MetricsEndpointIT.FixedTokenConfig.class)
class MetricsEndpointIT {

    static final String ADMIN_TOKEN = "it-admin-token";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    static String appdbUrl() { return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1"); }

    /** 覆盖 HostConfig 的 adminTokenFilter（它读 env），注入固定 token + 门控 /actuator/prometheus，使鉴权可被真实断言。 */
    @TestConfiguration
    static class FixedTokenConfig {
        @Bean
        FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
            FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(ADMIN_TOKEN));
            reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*",
                    "/resources/*", "/approvals/*", "/leases/*", "/monitor/*", "/actuator/prometheus");
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
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
        }
    }

    @LocalServerPort int port;
    @Autowired OperatorService op;
    @Autowired PolicyService policy;
    @Autowired TokenService tokens;

    /** 自建 RestTemplate：永不抛异常的 errorHandler 使 4xx/5xx 也返回 ResponseEntity 以便断言状态码。 */
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
    void prometheusAdminGatedHealthOpenAndDecisionsMetricAfterQuery() {
        RestTemplate http = http();

        // ① 无 token 访问 /actuator/prometheus → 401（filter 先于端点，即使 sealed）
        assertEquals(HttpStatus.UNAUTHORIZED, get(http, "/actuator/prometheus", null).getStatusCode());

        // ② 无 token GET /actuator/health → 200 含 "UP"（开放探活，不门控、不泄 seal 态）
        ResponseEntity<String> health = get(http, "/actuator/health", null);
        assertEquals(HttpStatus.OK, health.getStatusCode(), health.getBody());
        assertTrue(health.getBody().contains("UP"), health.getBody());

        // 解封（gauge 即便 sealed 也已在 @PostConstruct 注册，custos_seal_sealed 始终可暴露）
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1)); op.unseal(shares.get(2));
        assertFalse(op.status().sealed());

        // ③ 带 admin token GET /actuator/prometheus → 200 含 custos_seal_sealed（gauge 已注册暴露）
        ResponseEntity<String> prom = get(http, "/actuator/prometheus", ADMIN_TOKEN);
        assertEquals(HttpStatus.OK, prom.getStatusCode(), prom.getBody());
        assertTrue(prom.getBody().contains("custos_seal_sealed"), prom.getBody());

        // ④ 经一次真实 query_db：注册 appdb 资源 + 写策略 + 调 /query_db
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
        Map<String, String> queryBody = Map.of(
                "tool", "db/query_orders", "resource", "appdb",
                "sql", "SELECT COUNT(*) AS n FROM appdb.orders", "userToken", userTok);
        ResponseEntity<String> q = http.postForEntity(url("/query_db"),
                new HttpEntity<>(queryBody, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, q.getStatusCode(), q.getBody());
        assertTrue(q.getBody().contains("\"status\":\"ALLOWED\""), q.getBody());

        // query_db 后 prometheus 暴露 custos_decisions_total（counter 经 MicrometerBrokerMetrics 落地）
        ResponseEntity<String> promAfter = get(http, "/actuator/prometheus", ADMIN_TOKEN);
        assertEquals(HttpStatus.OK, promAfter.getStatusCode(), promAfter.getBody());
        assertTrue(promAfter.getBody().contains("custos_decisions_total"), promAfter.getBody());
    }

    private static HttpHeaders withAuth(HttpHeaders h, String token) {
        if (token != null) h.setBearerAuth(token);
        return h;
    }
}
