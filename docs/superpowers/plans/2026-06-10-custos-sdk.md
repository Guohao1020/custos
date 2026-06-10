# Custos SDK starter + CLI 完善（M13）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新模块 `sdk`（custos-spring-boot-starter：属性绑定 + 自动装配 + CustosClient）+ CLI 补 `query`/`operator seal` 子命令。

**Architecture:** starter 只做"一行依赖拿到 client"：`CustosClientProperties(custos.client.*)` → `CustosClientAutoConfiguration`（`@ConditionalOnMissingBean`）→ `CustosClient`（JDK HttpClient 打 host 既有 REST 端点）。测试用 `ApplicationContextRunner` + JDK 内置 `HttpServer` fake，纯单元无 Docker。

**Tech Stack:** Java 21 · Spring Boot 3.3.2（autoconfigure）· picocli（CLI 已有）· JUnit 5

> 前置：生产基座（host `/query_db`、`/operator/seal|status` 端点已存在）。对应 spec `docs/superpowers/specs/2026-06-10-custos-sdk-design.md`。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `pom.xml` | modules 加 `sdk` |
| `sdk/pom.xml` | artifactId custos-spring-boot-starter（spring-boot-autoconfigure + starter-test test）|
| `sdk/src/main/java/io/custos/sdk/CustosClientProperties.java` | custos.client.* 绑定 |
| `sdk/src/main/java/io/custos/sdk/CustosClient.java` | queryDb/operatorStatus |
| `sdk/src/main/java/io/custos/sdk/CustosClientAutoConfiguration.java` | 自动装配 |
| `sdk/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 注册 |
| `sdk/src/test/java/io/custos/sdk/{CustosClientAutoConfigurationTest,CustosClientTest}.java` | 测试 |
| `cli/src/main/java/io/custos/cli/CustosCli.java` | 加 query + operator seal |
| `cli/src/test/java/io/custos/cli/CustosCliHttpTest.java` | fake server 验请求形状 |

---

## Task 1: sdk 模块（属性 + client + 自动装配，TDD）

- [ ] **Step 1: 根 pom modules 加 `<module>sdk</module>`；写 sdk/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.custos</groupId><artifactId>custos-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>custos-spring-boot-starter</artifactId>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId><version>3.3.2</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><version>3.3.2</version><scope>test</scope></dependency>
  </dependencies>
</project>
```

- [ ] **Step 2: 写失败测试（自动装配三件套）**

```java
package io.custos.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class CustosClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustosClientAutoConfiguration.class));

    @Test
    void registersClientByDefault() {
        runner.run(ctx -> assertNotNull(ctx.getBean(CustosClient.class)));
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues("custos.client.base-url=http://h:9999", "custos.client.admin-token=t0")
                .run(ctx -> {
                    CustosClientProperties p = ctx.getBean(CustosClientProperties.class);
                    assertEquals("http://h:9999", p.getBaseUrl());
                    assertEquals("t0", p.getAdminToken());
                });
    }

    @Test
    void backsOffWhenUserDefinesOwnClient() {
        runner.withUserConfiguration(Custom.class)
                .run(ctx -> assertSame(Custom.MARKER, ctx.getBean(CustosClient.class)));
    }

    @Configuration
    static class Custom {
        static final CustosClient MARKER = new CustosClient("http://marker", "");
        @Bean CustosClient custosClient() { return MARKER; }
    }
}
```

- [ ] **Step 3: 实现三类 + imports 文件**

```java
package io.custos.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** custos.client.* 配置。 */
@ConfigurationProperties(prefix = "custos.client")
public class CustosClientProperties {
    private String baseUrl = "http://127.0.0.1:8080";
    private String adminToken = "";
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
    public String getAdminToken() { return adminToken; } public void setAdminToken(String v) { adminToken = v; }
}
```

```java
package io.custos.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Custos host 客户端：secretless——只透传 userToken 与查询，结果即行数据 JSON。 */
public final class CustosClient {
    private final String baseUrl;
    private final String adminToken;
    private final HttpClient http = HttpClient.newHttpClient();

    public CustosClient(String baseUrl, String adminToken) {
        this.baseUrl = baseUrl; this.adminToken = adminToken;
    }

    /** POST /query_db；返回响应体 JSON 文本（allowed/rows/denyReason）。 */
    public String queryDb(String tool, String schema, String sql, String userToken) {
        String body = "{\"tool\":" + q(tool) + ",\"schema\":" + q(schema)
                + ",\"sql\":" + q(sql) + ",\"userToken\":" + q(userToken) + "}";
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/query_db"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** GET /operator/status（admin）。 */
    public String operatorStatus() {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/operator/status"))
                .header("Authorization", "Bearer " + adminToken).GET().build());
    }

    private String send(HttpRequest req) {
        try { return http.send(req, HttpResponse.BodyHandlers.ofString()).body(); }
        catch (Exception e) { throw new IllegalStateException("custos host unreachable: " + baseUrl, e); }
    }
    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
```

```java
package io.custos.sdk;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(CustosClientProperties.class)
public class CustosClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public CustosClient custosClient(CustosClientProperties p) {
        return new CustosClient(p.getBaseUrl(), p.getAdminToken());
    }
}
```
`sdk/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
```
io.custos.sdk.CustosClientAutoConfiguration
```

- [ ] **Step 4: 绿（3 用例）→ 提交** `feat(sdk): custos-spring-boot-starter (properties + auto-config + client)`

---

## Task 2: CustosClient 请求形状测试（JDK HttpServer fake）

- [ ] **Step 1: 失败测试**

```java
package io.custos.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CustosClientTest {
    static HttpServer server;
    static AtomicReference<String> lastBody = new AtomicReference<>();
    static AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query_db", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.createContext("/operator/status", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"sealed\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.start();
    }
    @AfterAll static void stop() { server.stop(0); }

    private CustosClient client() { return new CustosClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok"); }

    @Test void queryDbPostsJsonShape() {
        assertEquals("{\"allowed\":true}", client().queryDb("db/q", "appdb", "SELECT 1", "jwt"));
        assertTrue(lastBody.get().contains("\"tool\":\"db/q\""));
        assertTrue(lastBody.get().contains("\"userToken\":\"jwt\""));
    }

    @Test void statusSendsBearer() {
        assertEquals("{\"sealed\":true}", client().operatorStatus());
        assertEquals("Bearer tok", lastAuth.get());
    }
}
```

- [ ] **Step 2: 绿 → 提交** `test(sdk): client request shape against in-JVM fake host`

---

## Task 3: CLI 补 query + operator seal

- [ ] **Step 1: `CustosCli` 顶层 subcommands 加 `Query.class`；`Operator` subcommands 加 `Operator.Seal.class`**

```java
    @Command(name = "query") static class Query implements Runnable {
        @Option(names = "--tool", required = true) String tool;
        @Option(names = "--schema", required = true) String schema;
        @Option(names = "--sql", required = true) String sql;
        @Option(names = "--user-token", required = true) String userToken;
        public void run() {
            try {
                System.out.println(post("/query_db", "{\"tool\":" + jsonStr(tool) + ",\"schema\":" + jsonStr(schema)
                        + ",\"sql\":" + jsonStr(sql) + ",\"userToken\":" + jsonStr(userToken) + "}"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    // Operator 内：
        @Command(name = "seal") static class Seal implements Runnable {
            public void run() { try { System.out.println(post("/operator/seal", "{}")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
```

- [ ] **Step 2: 测试（cli 模块加 junit 已有；fake server 同 Task 2 模式验 /query_db 与 /operator/seal 被打到）**

`cli/src/test/java/io/custos/cli/CustosCliHttpTest.java`：起 `HttpServer`，`new CommandLine(new CustosCli()).execute("--server", base, "query", "--tool", "t", "--schema", "s", "--sql", "SELECT 1", "--user-token", "j")` 断言 fake 收到路径 `/query_db` 且 body 含 `"tool":"t"`；`execute("--server", base, "--token", "tk", "operator", "seal")` 断言 `/operator/seal` + `Bearer tk`。

- [ ] **Step 3: 绿 + `mvn -B verify`（8 模块）→ demo.md「查询」节补 CLI 等价命令 → 提交** `feat(cli): query + operator seal subcommands`

---

## Self-Review（对照 SDK spec）

- 覆盖：属性/client/自动装配/imports(§3)→T1；请求形状(§5)→T2；CLI 两子命令(§3)→T3；错误处理(§4)→client 抛 IllegalState 用例隐含在 fake 模式（host 不可达即异常）。类型一致；无占位；无 Docker。backoff 语义有专测。
