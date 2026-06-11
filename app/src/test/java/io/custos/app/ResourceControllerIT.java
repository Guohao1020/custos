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
 * /resources REST 端到端（真端口 + Testcontainers MySQL）：
 * 鉴权（无 token→401）、注册、列表不漏密码、注册后真能 secretless 查询。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(ResourceControllerIT.FixedTokenConfig.class)
class ResourceControllerIT {

    static final String ADMIN_TOKEN = "it-admin-token";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    static String appdbUrl() { return MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/appdb$1"); }

    /** 覆盖 HostConfig 的 adminTokenFilter（它读 env），注入固定 token，使鉴权可被真实断言。 */
    @TestConfiguration
    static class FixedTokenConfig {
        @Bean
        FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
            FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(ADMIN_TOKEN));
            reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*", "/resources/*");
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
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
        }
    }

    @LocalServerPort int port;
    @Autowired OperatorService op;
    @Autowired PolicyService policy;
    @Autowired TokenService tokens;

    /**
     * 自建 RestTemplate：
     * - JdkClientHttpRequestFactory（基于 java.net.http.HttpClient）：避免旧 HttpURLConnection 在
     *   带 body 的 POST 收到 401 时触发认证重试抛 "cannot retry due to server authentication"；
     * - 永不抛异常的 errorHandler：4xx/5xx 也返回 ResponseEntity 以便断言状态码。
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

    @Test
    void unauthorizedThenRegisterThenListNoSecretThenSecretlessQuery() {
        RestTemplate http = http();

        // 解封
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1)); op.unseal(shares.get(2));
        assertFalse(op.status().sealed());

        String secret = "root";   // 目标库高权限密码，绝不应出现在任何响应里
        Map<String, Object> resourceBody = Map.of(
                "name", "appdb", "type", "db.relational", "dialect", "mysql",
                "jdbcUrl", appdbUrl(), "adminUsername", "root", "adminPassword", secret,
                "roles", List.of(Map.of(
                        "name", "read-only", "kind", "BUILTIN_READONLY",
                        "creationStatements", List.of(), "revocationStatements", List.of(),
                        "defaultTtlSeconds", 3600, "schema", "appdb")));

        // 无 token → 401
        ResponseEntity<String> noTok = http.postForEntity(url("/resources"), json(resourceBody, null), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noTok.getStatusCode());

        // 带 admin token → 注册成功
        ResponseEntity<String> reg = http.postForEntity(url("/resources"), json(resourceBody, ADMIN_TOKEN), String.class);
        assertEquals(HttpStatus.OK, reg.getStatusCode(), reg.getBody());
        assertTrue(reg.getBody().contains("registered"));

        // GET /resources 含 appdb，且响应体绝不含密码
        ResponseEntity<String> list = http.exchange(url("/resources"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(ADMIN_TOKEN)), String.class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertTrue(list.getBody().contains("appdb"));
        assertFalse(list.getBody().contains(secret), "列表响应绝不能泄露 adminPassword");

        // 写策略允许 claude-prod 只读
        policy.put("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default\n");

        // POST /query_db（resource=appdb）拿到行、secretless
        String userTok = tokens.issue(new AgentId("corp.example", "claude-prod", "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
        Map<String, String> queryBody = Map.of(
                "tool", "db/query_orders", "resource", "appdb",
                "sql", "SELECT COUNT(*) AS n FROM appdb.orders", "userToken", userTok);
        ResponseEntity<String> q = http.postForEntity(url("/query_db"),
                new HttpEntity<>(queryBody, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, q.getStatusCode(), q.getBody());
        assertTrue(q.getBody().contains("\"status\":\"ALLOWED\""), q.getBody());
        assertTrue(q.getBody().contains("\"n\":3"), q.getBody());
        assertFalse(q.getBody().contains("v_ro_"), "查询响应绝不能泄露临时凭证用户名");
    }

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

    private static HttpEntity<Map<String, Object>> json(Map<String, Object> body, String token) {
        HttpHeaders h = jsonHeaders();
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(body, h);
    }
}
