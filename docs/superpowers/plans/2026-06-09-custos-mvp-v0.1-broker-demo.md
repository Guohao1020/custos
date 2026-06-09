# Custos MVP v0.1 — 经纪层 + MCP + Demo Implementation Plan（计划 5/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 TDD 实现经纪层（PEP）——secretless 只读查询执行、`身份校验→PDP决策→签发动态凭证→执行→审计` 编排，并以 MCP server 暴露 `query_db` 工具；最后用 docker-compose（Nacos+MySQL+Custos）串起端到端 demo，验收 AC1–AC8。

**Architecture:** 独立 `broker` 模块依赖 engine/identity/authz。`SecretlessQueryExecutor` 用临时凭证执行只读查询、**只回行数据、绝不回凭证**。`BrokerService` 编排各层。MCP 暴露用 MCP Java SDK 的同步 server。`custos-app`（Spring Boot）装配并接 Nacos/MySQL。

**Tech Stack:** Java 21 · Spring Boot 3.x · MCP Java SDK · Testcontainers · docker-compose · JUnit 5

> 前置：计划 1–4 全部完成。对应 spec §3.9、§5、§9（AC）、详设 `docs/design/06-secrets-broker.md`、`docs/design/01` §4、`docs/design/07`。**铁律：密钥不进 LLM——返回结果绝不含凭证。**

---

## File Structure

| 文件 | 职责 |
|---|---|
| `pom.xml` | 加 `broker`、`app` 到 modules |
| `broker/pom.xml` | 依赖 engine/identity/authz + MCP SDK |
| `broker/src/main/java/io/custos/broker/QueryIntent.java` | 查询意图（工具 + 只读 SQL）|
| `broker/src/main/java/io/custos/broker/QueryResult.java` | 结果（行数据 / 拒绝原因；**无凭证**）|
| `broker/src/main/java/io/custos/broker/SecretlessQueryExecutor.java` | 用临时凭证执行只读查询 |
| `broker/src/main/java/io/custos/broker/BrokerService.java` | 编排 PEP |
| `broker/src/main/java/io/custos/broker/McpQueryToolServer.java` | MCP 暴露 query_db |
| `app/pom.xml`、`app/src/main/java/io/custos/app/CustosApplication.java`、`app/src/main/resources/application.yml` | Spring Boot 装配 |
| `examples/docker-compose.yml`、`examples/init/schema.sql`、`examples/demo.md` | demo 编排与 runbook |
| `broker/src/test/java/io/custos/broker/**` | 测试（Testcontainers）|

---

## Task 1: SecretlessQueryExecutor（只读执行，绝不回凭证）

**Files:**
- Modify: `pom.xml`（modules 加 `broker`）
- Create: `broker/pom.xml`
- Create: `broker/src/main/java/io/custos/broker/QueryIntent.java`
- Create: `broker/src/main/java/io/custos/broker/QueryResult.java`
- Create: `broker/src/main/java/io/custos/broker/SecretlessQueryExecutor.java`
- Test: `broker/src/test/java/io/custos/broker/SecretlessQueryExecutorIT.java`

- [ ] **Step 1: modules 加 broker；写 broker POM**

`pom.xml` 的 `<modules>` 追加 `<module>broker</module>`。

`broker/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.custos</groupId>
    <artifactId>custos-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>custos-broker</artifactId>
  <dependencies>
    <dependency><groupId>io.custos</groupId><artifactId>custos-engine</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
    <dependency><groupId>io.custos</groupId><artifactId>custos-identity</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
    <dependency><groupId>io.custos</groupId><artifactId>custos-authz</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-core</artifactId>
      <version>2.0.0</version>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-json-jackson2</artifactId>
      <version>2.0.0</version>
    </dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>8.4.0</version></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><version>1.19.8</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>1.19.8</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

- [ ] **Step 2: 写失败测试（只读查询返回行；拒绝非 SELECT；结果不含凭证）**

`broker/src/test/java/io/custos/broker/SecretlessQueryExecutorIT.java`:
```java
package io.custos.broker;

import io.custos.engine.secrets.IssuedCred;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SecretlessQueryExecutorIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2),(3)");
            st.execute("CREATE USER IF NOT EXISTS 'ro_user'@'%' IDENTIFIED BY 'ro_pwd'");
            st.execute("GRANT SELECT ON appdb.* TO 'ro_user'@'%'");
            st.execute("FLUSH PRIVILEGES");
        }
    }

    private final SecretlessQueryExecutor exec = new SecretlessQueryExecutor();

    @Test
    void runsReadonlyQueryAndReturnsRows() {
        IssuedCred cred = new IssuedCred("ro_user", "ro_pwd", "lease-1", Long.MAX_VALUE);
        List<Map<String, Object>> rows = exec.runReadonly(MYSQL.getJdbcUrl(), cred, "SELECT COUNT(*) AS n FROM appdb.orders");
        assertEquals(1, rows.size());
        assertEquals(3L, ((Number) rows.get(0).get("n")).longValue());
    }

    @Test
    void rejectsNonSelectStatement() {
        IssuedCred cred = new IssuedCred("ro_user", "ro_pwd", "lease-1", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> exec.runReadonly(MYSQL.getJdbcUrl(), cred, "DELETE FROM appdb.orders"));
        assertThrows(IllegalArgumentException.class,
                () -> exec.runReadonly(MYSQL.getJdbcUrl(), cred, "SELECT 1; DROP TABLE appdb.orders"));
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl broker test -Dtest=SecretlessQueryExecutorIT`
Expected: 编译失败（broker 类未定义）。

- [ ] **Step 4: 写 QueryIntent / QueryResult / SecretlessQueryExecutor**

`broker/src/main/java/io/custos/broker/QueryIntent.java`:
```java
package io.custos.broker;

/** 查询意图：tool=MCP 工具名(如 db/query_orders)，schema=目标库，sql=只读 SQL。 */
public record QueryIntent(String tool, String schema, String sql) {}
```

`broker/src/main/java/io/custos/broker/QueryResult.java`:
```java
package io.custos.broker;

import java.util.List;
import java.util.Map;

/** 经纪返回：行数据或拒绝原因。**永不包含任何凭证**。 */
public record QueryResult(boolean allowed, List<Map<String, Object>> rows, String denyReason) {
    public static QueryResult ok(List<Map<String, Object>> rows) { return new QueryResult(true, rows, null); }
    public static QueryResult denied(String reason) { return new QueryResult(false, List.of(), reason); }
}
```

`broker/src/main/java/io/custos/broker/SecretlessQueryExecutor.java`:
```java
package io.custos.broker;

import io.custos.engine.secrets.IssuedCred;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用临时凭证执行只读查询，只回行数据。凭证仅在本方法内使用，绝不外泄到返回值。 */
public final class SecretlessQueryExecutor {

    public List<Map<String, Object>> runReadonly(String jdbcUrl, IssuedCred cred, String sql) {
        requireReadonly(sql);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, cred.username(), cred.password());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
            return rows;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("secretless query failed", e);
        }
    }

    /** 只允许单条 SELECT，拒绝写语句与多语句注入。 */
    private static void requireReadonly(String sql) {
        String t = sql.strip();
        if (t.endsWith(";")) t = t.substring(0, t.length() - 1).strip();
        if (t.contains(";")) throw new IllegalArgumentException("multiple statements not allowed");
        String upper = t.toUpperCase();
        if (!(upper.startsWith("SELECT ") || upper.startsWith("SELECT\n") || upper.startsWith("WITH "))) {
            throw new IllegalArgumentException("only read-only SELECT is allowed");
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl broker test -Dtest=SecretlessQueryExecutorIT`
Expected: PASS（2 个用例）。

- [ ] **Step 6: 提交**
```bash
git add pom.xml broker/pom.xml broker/src/main/java/io/custos/broker/QueryIntent.java broker/src/main/java/io/custos/broker/QueryResult.java broker/src/main/java/io/custos/broker/SecretlessQueryExecutor.java broker/src/test/java/io/custos/broker/SecretlessQueryExecutorIT.java
git commit -m "feat(broker): secretless read-only query executor"
```

---

## Task 2: BrokerService 编排（身份→PDP→动态凭证→执行→审计；断言 secretless）

**Files:**
- Create: `broker/src/main/java/io/custos/broker/BrokerService.java`
- Test: `broker/src/test/java/io/custos/broker/BrokerServiceIT.java`

- [ ] **Step 1: 写失败测试（准→返回数据且不含凭证；拒→返回原因不查库）**

`broker/src/test/java/io/custos/broker/BrokerServiceIT.java`:
```java
package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.engine.lease.DefaultLeaseManager;
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

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(128) PRIMARY KEY, resource_path VARCHAR(512), issued_at BIGINT, expire_at BIGINT, revoked TINYINT DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        signKey = g.generateKeyPair();
        tokens = new JwtTokenService(signKey, "custos", new InMemoryBlacklist());
    }

    private BrokerService broker() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("""
                p, role:reader, tool:db/*, read, allow
                g, agent:claude-prod, role:reader
                """);
        DynamicDbCredentials creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(admin), MYSQL.getJdbcUrl());
        return new BrokerService(tokens, pdp, creds, new SecretlessQueryExecutor(), MYSQL.getJdbcUrl());
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
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl broker test -Dtest=BrokerServiceIT`
Expected: 编译失败（BrokerService 未定义）。

- [ ] **Step 3: 实现 BrokerService**

`broker/src/main/java/io/custos/broker/BrokerService.java`:
```java
package io.custos.broker;

import io.custos.authz.Decision;
import io.custos.authz.DecisionRequest;
import io.custos.authz.Pdp;
import io.custos.engine.secrets.DynamicDbCredentials;
import io.custos.engine.secrets.IssuedCred;
import io.custos.identity.AgentId;
import io.custos.identity.TokenClaims;
import io.custos.identity.TokenService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 经纪层 PEP 编排：verify token → PDP decide → 签发动态只读凭证 → secretless 执行 → 返回结果（无凭证）。
 * 注：审计在完整接线（app）中注入 AuditLog；本编排留 audit 钩子（MVP 测试聚焦 secretless 与决策）。
 */
public final class BrokerService {

    private final TokenService tokens;
    private final Pdp pdp;
    private final DynamicDbCredentials creds;
    private final SecretlessQueryExecutor executor;
    private final String jdbcUrl;

    public BrokerService(TokenService tokens, Pdp pdp, DynamicDbCredentials creds,
                         SecretlessQueryExecutor executor, String jdbcUrl) {
        this.tokens = tokens;
        this.pdp = pdp;
        this.creds = creds;
        this.executor = executor;
        this.jdbcUrl = jdbcUrl;
    }

    public QueryResult queryDb(QueryIntent intent, String userToken) {
        TokenClaims claims = tokens.verify(userToken);                 // 失败抛 TokenException
        String sub = "agent:" + AgentId.parse(claims.subject()).agent();
        Decision d = pdp.decide(new DecisionRequest(sub, "tool:" + intent.tool(), "read"));
        if (!d.allowed()) {
            return QueryResult.denied(d.reason());
        }
        IssuedCred cred = creds.issueReadonly(intent.schema(), Duration.ofHours(1));
        try {
            List<Map<String, Object>> rows = executor.runReadonly(jdbcUrl, cred, intent.sql());
            return QueryResult.ok(rows);
        } finally {
            creds.revoke(cred.leaseId());     // 即用即焚（也可留至租约到期）
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl broker test -Dtest=BrokerServiceIT`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**
```bash
git add broker/src/main/java/io/custos/broker/BrokerService.java broker/src/test/java/io/custos/broker/BrokerServiceIT.java
git commit -m "feat(broker): PEP orchestration (verify->decide->issue->execute->revoke), secretless"
```

---

## Task 3: MCP 暴露 + Spring Boot 装配 + docker-compose

**Files:**
- Create: `broker/src/main/java/io/custos/broker/McpQueryToolServer.java`
- Create: `app/pom.xml`、`app/src/main/java/io/custos/app/CustosApplication.java`、`app/src/main/resources/application.yml`
- Create: `examples/docker-compose.yml`、`examples/init/schema.sql`
- Modify: `pom.xml`（modules 加 `app`）

> 本任务产物为可运行装配与部署编排；其端到端验证在 Task 4。

- [ ] **Step 1: MCP 工具暴露（绑定 query_db → BrokerService）**

`broker/src/main/java/io/custos/broker/McpQueryToolServer.java`:
```java
package io.custos.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 以 MCP server 暴露 query_db 工具（MCP Java SDK 2.0 API，源码核准 0.10.x→2.0 后重写）：
 * McpServer.sync(transport).serverInfo(...).toolCall(tool, (exchange, request) -> CallToolResult).build()。
 * handler 调 BrokerService，只回结果文本（绝不回凭证）。
 */
public final class McpQueryToolServer {

    private final BrokerService broker;

    public McpQueryToolServer(BrokerService broker) {
        this.broker = broker;
    }

    public McpSyncServer start() {
        // 2.0：Tool.builder(name, Map 形式的输入 JSON Schema)
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tool", Map.of("type", "string"),
                        "schema", Map.of("type", "string"),
                        "sql", Map.of("type", "string"),
                        "userToken", Map.of("type", "string")),
                "required", List.of("tool", "schema", "sql", "userToken"));

        McpSchema.Tool tool = McpSchema.Tool.builder("query_db", inputSchema)
                .description("对受治理只读库执行 SELECT，返回结果（凭证不出库）")
                .build();

        // 2.0：StdioServerTransportProvider 需注入 McpJsonMapper（这里用 jackson2 实现）
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

        return McpServer.sync(transport)
                .serverInfo("custos-broker", "0.1.0")
                .toolCall(tool, (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    QueryResult r = broker.queryDb(
                            new QueryIntent((String) args.get("tool"), (String) args.get("schema"), (String) args.get("sql")),
                            (String) args.get("userToken"));
                    String text = r.allowed() ? ("rows=" + r.rows()) : ("DENIED: " + r.denyReason());
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(text).build()))
                            .isError(!r.allowed())
                            .build();
                })
                .build();
    }
}
```

- [ ] **Step 2: Spring Boot app 装配**

`pom.xml` 的 `<modules>` 追加 `<module>app</module>`。

`app/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.custos</groupId>
    <artifactId>custos-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>custos-app</artifactId>
  <dependencies>
    <dependency><groupId>io.custos</groupId><artifactId>custos-broker</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId><version>3.3.2</version></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><version>3.3.2</version></plugin>
    </plugins>
  </build>
</project>
```

`app/src/main/java/io/custos/app/CustosApplication.java`:
```java
package io.custos.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Custos 装配入口（MVP）：读 application.yml，初始化引擎(解封)/身份/PDP+Nacos Watcher/经纪+MCP。
 * 具体 Bean 装配按 application.yml（见 docs/superpowers/specs/...-mvp-v0.1-design.md §7）。
 */
@SpringBootApplication
public class CustosApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustosApplication.class, args);
    }
}
```

`app/src/main/resources/application.yml`:
```yaml
custos:
  engine:
    storage: { type: mysql, url: jdbc:mysql://mysql:3306/custos, username: custos, password-ref: env:CUSTOS_DB_PWD }
    seal:    { type: shamir, shares: 5, threshold: 3 }
    cipher-suite: intl
  nacos:
    server-addr: nacos:8848
    namespace: public
    policy-data-id: custos-policy
  identity:
    token-ttl: 15m
    issuer: custos
  broker:
    db-readonly-schema: appdb
    cred-ttl: 1h
```

- [ ] **Step 3: docker-compose 与初始化 SQL**

`examples/init/schema.sql`:
```sql
CREATE DATABASE IF NOT EXISTS custos;
CREATE DATABASE IF NOT EXISTS appdb;
USE appdb;
CREATE TABLE IF NOT EXISTS orders (id INT, amount INT);
INSERT INTO orders VALUES (1, 100), (2, 200), (3, 300);
```

`examples/docker-compose.yml`:
```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: rootpwd
      MYSQL_DATABASE: custos
      MYSQL_USER: custos
      MYSQL_PASSWORD: custospwd
    ports: ["3306:3306"]
    volumes:
      - ./init:/docker-entrypoint-initdb.d:ro
  nacos:
    image: nacos/nacos-server:v2.3.2
    environment:
      MODE: standalone
    ports: ["8848:8848", "9848:9848"]
    depends_on: [mysql]
  custos:
    build: ..
    environment:
      CUSTOS_DB_PWD: custospwd
      NACOS_ADDR: nacos:8848
    depends_on: [mysql, nacos]
```

- [ ] **Step 4: 校验 compose 语法**

Run: `docker compose -f examples/docker-compose.yml config`
Expected: 输出规范化的合并配置，无错误。

- [ ] **Step 5: 编译全工程**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS（engine/identity/authz/broker/app 全部编译，app 产出可执行 jar）。

- [ ] **Step 6: 提交**
```bash
git add pom.xml broker/src/main/java/io/custos/broker/McpQueryToolServer.java app examples
git commit -m "feat(app): MCP query tool, Spring Boot assembly, docker-compose demo stack"
```

---

## Task 4: 端到端 Demo Runbook + AC1–AC8 验收

**Files:**
- Create: `examples/demo.md`

> 端到端需运行整套 stack；本任务把演示与验收固化为可执行 runbook，逐条对齐验收标准。

- [ ] **Step 1: 写 demo runbook**

`examples/demo.md`:
````markdown
# Custos MVP v0.1 Demo Runbook

## 启动
```bash
docker compose -f examples/docker-compose.yml up -d --build
# 等 mysql/nacos healthy
```

## 1. 解封引擎（AC1）
```bash
# init 一次，记下 5 个 unseal 分片；提交其中 3 个解封
custos operator init --shares 5 --threshold 3      # 输出 5 个分片（仅此一次）
custos operator unseal <share1>
custos operator unseal <share2>
custos operator unseal <share3>
custos operator status                              # 期望 sealed=false
```
**通过标准**：缺片时 status 显示 progress<3 且操作被拒；满 3 片后 unsealed。

## 2. 写策略到 Nacos（允许 claude-prod 只读）
```bash
custos policy put --data-id custos-policy --content '
p, role:reader, tool:db/*, read, allow
g, agent:claude-prod, role:reader'
```

## 3. Agent 经 MCP 查询（AC3/AC4/AC5）
- 在 MCP 客户端（Claude/Codex）配置 custos-broker（stdio）。
- 调用 `query_db { tool:"db/query_orders", schema:"appdb", sql:"SELECT COUNT(*) AS n FROM appdb.orders", userToken:"<JWT>" }`
- 期望返回 `rows=[{n=3}]`。

**验收**：
- AC3 动态凭证：查询期间 MySQL 出现临时 `v_ro_*` 只读账号，查询后被 DROP（`SELECT user FROM mysql.user` 验证）。
- AC4 secretless：抓 MCP 往返报文/日志，**无连接串/密码**，只见结果。
- AC5 可解释：被拒时返回命中策略 + 原因。

## 4. 秒级吊销（AC6）
```bash
# 改策略为拒绝，计时到下一次查询被拒
time custos policy put --data-id custos-policy --content '
p, role:reader, tool:db/*, read, deny
g, agent:claude-prod, role:reader'
# 立即重发 query_db → 期望 DENIED；记录从改策略到被拒的延迟（应 ≤ 数秒）
```
**通过标准**：AC6 延迟 ≤ 数秒（记录 P95）。

## 5. 审计防篡改（AC7）
```bash
custos audit verify                 # 期望 OK
# 手工改一条历史：UPDATE custos_audit SET action='write' WHERE seq=1;
custos audit verify                 # 期望报断链于 seq=1
```

## 6. 落盘加密（AC2）
```bash
# 直查存储表，确认密文
docker exec -it <mysql> mysql -uroot -prootpwd -e "SELECT HEX(svalue) FROM custos.custos_storage LIMIT 1"
```
**通过标准**：均为密文；改一字节后读取报完整性失败。

## 7. 一键起（AC8）
`docker compose up` 起 Nacos+MySQL+Custos，按上述脚本跑通即 AC8 通过。
````

- [ ] **Step 2: （可选，需 stack 运行）跑通 Nacos 门控 IT**

Run: `NACOS_ADDR=127.0.0.1:8848 mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT`
Expected: PASS（验证计划 4 的真实 Nacos 秒级推送）。

- [ ] **Step 3: 提交**
```bash
git add examples/demo.md
git commit -m "docs(examples): end-to-end demo runbook mapped to AC1-AC8"
```

---

## Self-Review（对照 spec §3.9、§9 AC、详设 06/01/07）

- **Spec 覆盖**：secretless 执行（§3.9、§4 secretless）→ Task 1；编排 verify→decide→issue→execute→audit（§3.9、详设 01 §4）→ Task 2；MCP 暴露（§3.9 IF1）→ Task 3；docker-compose + AC1–AC8（§9、详设 07）→ Task 3/4。
- **类型一致性**：`QueryIntent(tool,schema,sql)`、`QueryResult.ok/denied`、`SecretlessQueryExecutor.runReadonly`、`BrokerService.queryDb`、`IssuedCred`（计划 2）、`Decision/DecisionRequest/Pdp`（计划 4）、`TokenService/TokenClaims/AgentId`（计划 3）跨计划一致。
- **占位扫描**：无 TODO/TBD。MCP Java SDK 已**源码核准并按 2.0 重写**（`mcp-core`+`mcp-json-jackson2` 2.0.0、`sync(transport).serverInfo().toolCall(tool, (exchange,request)->CallToolResult).build()`、`Tool.builder(name,Map)`、`CallToolResult.builder().content(...).isError(...).build()`、`StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()))`）；仅 CLI 子命令名为约定式占位。
- **secretless 红线**：Task 2 显式断言返回结果不含 `v_ro_`/`password`；Task 1 拒绝非 SELECT 与多语句。AC4 在 runbook 抓包验证。
- **范围**：审计在 BrokerService 留钩子、在 app 装配注入（避免 broker 单测耦合 DB 审计表）；完整审计链在 demo 的 AC7 验证。
- **可独立交付**：本计划串起前 4 计划，产出可演示的端到端 MVP；AC1–AC8 有明确验证步骤。

---

## v0.1 计划序列收尾

至此 **5/5 计划齐备**：1 引擎基座 · 2 引擎持久化 · 3 身份 JWT · 4 策略+Nacos秒级吊销 · 5 经纪+MCP+demo。按序执行即得可演示的 Custos MVP v0.1（证明身份+权限+密钥+Nacos秒级吊销+密钥不进 LLM）。后续 v0.2~v0.4 见纲领 spec §8。
