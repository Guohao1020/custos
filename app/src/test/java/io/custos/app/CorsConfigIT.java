package io.custos.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 预检端到端（真端口）：
 * CORS 是 filter/MVC 层，OPTIONS 预检不进 controller，故无需解封、无需 MySQL 容器。
 * 校验：放行可配 console 源、Authorization 头被允许、OPTIONS 预检不被 AdminTokenFilter 401 拦、
 * 非配置源不回带 Access-Control-Allow-Origin。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("custos.console.origin", () -> "http://localhost:5173");
        r.add("custos.nacos.server-addr", () -> "");   // 空 → InMemoryControlPlane，启动不依赖 Nacos
    }

    @LocalServerPort int port;

    /** 永不抛异常的 RestTemplate，4xx/5xx 也返回 ResponseEntity 以便断言。 */
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
    void preflightFromConfiguredOriginGetsCorsHeaders() {
        RestTemplate http = http();

        // OPTIONS 预检：来自可配置源 http://localhost:5173，请求方法 GET
        HttpHeaders h = new HttpHeaders();
        h.setOrigin("http://localhost:5173");
        h.set("Access-Control-Request-Method", "GET");
        h.set("Access-Control-Request-Headers", "Authorization");
        ResponseEntity<Void> r = http.exchange(url("/monitor/stats"), HttpMethod.OPTIONS,
                new HttpEntity<>(h), Void.class);

        // 预检不被 AdminTokenFilter 401 拦（应是 2xx），且回带的源精确等于该 console 源（非通配符）
        assertTrue(r.getStatusCode().is2xxSuccessful(), "preflight must not be blocked by AdminTokenFilter, got " + r.getStatusCode());
        assertEquals("http://localhost:5173", r.getHeaders().getAccessControlAllowOrigin());
        // Authorization 头被允许
        assertTrue(r.getHeaders().getAccessControlAllowHeaders().stream()
                        .anyMatch(s -> s.equalsIgnoreCase("Authorization")),
                "Access-Control-Allow-Headers must include Authorization, got " + r.getHeaders().getAccessControlAllowHeaders());
    }

    @Test
    void preflightFromUnconfiguredOriginGetsNoCorsAllowOrigin() {
        RestTemplate http = http();

        HttpHeaders h = new HttpHeaders();
        h.setOrigin("http://evil.example");
        h.set("Access-Control-Request-Method", "GET");
        ResponseEntity<Void> r = http.exchange(url("/monitor/stats"), HttpMethod.OPTIONS,
                new HttpEntity<>(h), Void.class);

        // 非配置源：绝不能回带把它当成允许源的 CORS 头
        assertNotEquals("http://evil.example", r.getHeaders().getAccessControlAllowOrigin());
    }
}
