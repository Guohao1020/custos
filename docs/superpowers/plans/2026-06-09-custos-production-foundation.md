# Custos 生产基座（Production Foundation）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 v0.1 五模块收敛为「模块化单体 + SPI 插件」的可端到端运行生产基座：SecretsEngine SPI 化、`custos-host`（启动即 sealed 的 Spring Boot 服务 + REST admin 解封流 + secretless 查询面）、MCP transport、`custos-cli`，并以 docker-compose 跑通 AC1–AC8。

**Architecture:** 三圈单向依赖（transport→编排→SPI 内核）。**解封生命周期是核心**：`SealManager`/`JimmerSealStore` 启动即建（仅需 DataSource）；`Barrier`/`JimmerStorage`/`SecretsEngine`/`AuditLog`/`BrokerService` 依赖解封后的 keyring，由 `OperatorService` 在 unseal 成功后装配并持有于 `AtomicReference`。未解封时查询面返回 HTTP 409。admin 面用轻量 Bearer-token Filter 保护（不引 Spring Security）。

**Tech Stack:** Java 21 · Spring Boot 3.3.2（web）· picocli 4.7.6 · MCP Java SDK 2.0.0-RC1 · jcasbin 1.55.0 · nacos-client 2.3.2 · Jimmer 0.10.10 · Testcontainers 1.19.8 · JUnit 5

> 前置：v0.1（engine/identity/authz/broker/app 已合并 main）。对应 `docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md` §3/§5/§8。
> **依赖解析约定**：本计划新增/改动跨模块依赖，运行单模块测试前先 `mvn -N install` + `mvn -pl engine,identity,authz,broker install -Dmaven.test.skip=true` 把工件装进本地仓库（reactor `verify` 不 install）。
> **Docker 约定**：Testcontainers 需 `~/.testcontainers.properties` 配 `docker.host` 且各含 IT 的模块 pom 钉 `api.version=1.40`（见 engine/broker pom）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/src/main/java/io/custos/engine/secrets/SecretsEngine.java` | 密钥引擎 SPI（type/issue/revoke）|
| `engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java` | 按 mount 名注册/取引擎 |
| `engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java` | 改造为 implements SecretsEngine |
| `broker/src/main/java/io/custos/broker/BrokerService.java` | 依赖 `SecretsEngine`（非具体类）|
| `host/pom.xml` ←由 `app/pom.xml` 演进 | 加 spring-boot-starter-web + broker/authz + nacos |
| `host/src/main/java/io/custos/app/CustosApplication.java` | 入口（保留）|
| `host/src/main/java/io/custos/app/config/CustosProperties.java` | 绑定 `custos.*` 配置 |
| `host/src/main/java/io/custos/app/engine/EngineBootstrap.java` | 启动期建 DataSource/JSqlClient/SealStore/SealManager（sealed）|
| `host/src/main/java/io/custos/app/engine/UnsealedContext.java` | 解封后运营组件持有体 |
| `host/src/main/java/io/custos/app/operator/OperatorService.java` | init/unseal/seal/status + 解封后装配 |
| `host/src/main/java/io/custos/app/operator/OperatorController.java` | REST `/operator/*` |
| `host/src/main/java/io/custos/app/policy/PolicyService.java` + `PolicyController.java` | `/policy` 写 ControlPlane |
| `host/src/main/java/io/custos/app/audit/AuditController.java` | `/audit/verify` |
| `host/src/main/java/io/custos/app/query/QueryController.java` | `/query_db` → BrokerService |
| `host/src/main/java/io/custos/app/security/AdminTokenFilter.java` | Bearer-token 保护 admin 面 |
| `host/src/main/java/io/custos/app/mcp/McpStdioRunner.java` | 按属性启动 MCP stdio transport |
| `host/src/test/java/io/custos/app/HostEndToEndIT.java` | 端到端宿主集成测试（Testcontainers）|
| `cli/pom.xml` + `cli/src/main/java/io/custos/cli/*.java` | picocli `operator/policy/audit` 打 REST admin |
| `examples/docker-compose.yml`、`examples/demo.md` | 真命令对齐 AC1–AC8 |

---

## Task 1: SecretsEngine SPI + Registry + BrokerService 解耦

**Files:**
- Create: `engine/src/main/java/io/custos/engine/secrets/SecretsEngine.java`
- Create: `engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java`
- Modify: `engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java`
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`
- Modify: `broker/src/test/java/io/custos/broker/BrokerServiceIT.java`
- Test: `engine/src/test/java/io/custos/engine/secrets/SecretsEngineRegistryTest.java`

- [ ] **Step 1: 写 SecretsEngine SPI**

`engine/src/main/java/io/custos/engine/secrets/SecretsEngine.java`:
```java
package io.custos.engine.secrets;

import java.time.Duration;

/** 密钥引擎 SPI：按挂载路径签发/撤销现场凭证。DB/AK·SK/KV 等各实现一份。 */
public interface SecretsEngine {
    /** 引擎类型标识，如 "db-readonly"。 */
    String type();

    /** 在给定 path（如 schema 名）上签发 TTL 凭证。 */
    IssuedCred issue(String path, Duration ttl);

    /** 按 leaseId 撤销（触发底层清理，如 DROP USER）。 */
    void revoke(String leaseId);
}
```

- [ ] **Step 2: 写 Registry 的失败测试**

`engine/src/test/java/io/custos/engine/secrets/SecretsEngineRegistryTest.java`:
```java
package io.custos.engine.secrets;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SecretsEngineRegistryTest {

    private SecretsEngine stub(String type) {
        return new SecretsEngine() {
            public String type() { return type; }
            public IssuedCred issue(String path, Duration ttl) { return new IssuedCred("u", "p", "l", 0L); }
            public void revoke(String leaseId) { }
        };
    }

    @Test
    void mountAndResolveByName() {
        SecretsEngineRegistry reg = new SecretsEngineRegistry();
        reg.mount("db", stub("db-readonly"));
        assertEquals("db-readonly", reg.require("db").type());
    }

    @Test
    void unknownMountThrows() {
        SecretsEngineRegistry reg = new SecretsEngineRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.require("nope"));
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=SecretsEngineRegistryTest`
Expected: 编译失败（SecretsEngineRegistry 未定义）。

- [ ] **Step 4: 写 SecretsEngineRegistry**

`engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java`:
```java
package io.custos.engine.secrets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 mount 名持有多个 SecretsEngine（生产可挂多个：db/、akSk/、kv/）。 */
public final class SecretsEngineRegistry {

    private final Map<String, SecretsEngine> mounts = new ConcurrentHashMap<>();

    public void mount(String name, SecretsEngine engine) {
        mounts.put(name, engine);
    }

    public SecretsEngine require(String name) {
        SecretsEngine e = mounts.get(name);
        if (e == null) throw new IllegalArgumentException("no secrets engine mounted at: " + name);
        return e;
    }
}
```

- [ ] **Step 5: 改 DynamicDbCredentials 实现 SecretsEngine**

在 `DynamicDbCredentials` 类声明改为 `implements SecretsEngine`，并新增三个接口方法（复用既有逻辑）：
```java
public final class DynamicDbCredentials implements io.custos.engine.secrets.SecretsEngine {
    // ... 既有字段与构造器、issueReadonly、revoke、dropUser、randomHex 不变 ...

    @Override
    public String type() { return "db-readonly"; }

    @Override
    public IssuedCred issue(String path, java.time.Duration ttl) { return issueReadonly(path, ttl); }

    // revoke(String leaseId) 既有方法已满足接口，无需新增
}
```
> 既有 `public void revoke(String leaseId)` 正好满足接口；只需加 `type()` 与 `issue(...)`，并在 class 声明加 `implements SecretsEngine`。

- [ ] **Step 6: BrokerService 依赖 SecretsEngine（非具体类）**

`broker/src/main/java/io/custos/broker/BrokerService.java` 把字段与构造参数 `DynamicDbCredentials creds` 改为 `SecretsEngine creds`，调用处 `creds.issueReadonly(schema, ttl)` 改为 `creds.issue(schema, ttl)`（`revoke` 不变）：
```java
import io.custos.engine.secrets.SecretsEngine;
// ...
    private final SecretsEngine creds;
    public BrokerService(TokenService tokens, Pdp pdp, SecretsEngine creds,
                         SecretlessQueryExecutor executor, String jdbcUrl) { /* 同前赋值 */ }
// queryDb 内：
        IssuedCred cred = creds.issue(intent.schema(), Duration.ofHours(1));
```

- [ ] **Step 7: 同步 BrokerServiceIT 构造（DynamicDbCredentials 仍可传入，因其 implements SecretsEngine）**

`BrokerServiceIT.broker()` 无需改动逻辑——`new DynamicDbCredentials(...)` 现已是 `SecretsEngine`，可直接传给 `new BrokerService(..., creds, ...)`。确认 import 不变即可。

- [ ] **Step 8: 装本地仓库 + 跑测试**

Run:
```
mvn -N install -Dmaven.test.skip=true
mvn -pl engine install -Dmaven.test.skip=true
mvn -pl engine test -Dtest=SecretsEngineRegistryTest
mvn -pl broker -am test -Dtest=BrokerServiceIT
```
Expected: SecretsEngineRegistryTest PASS（2）；BrokerServiceIT PASS（2，经 SecretsEngine 间接调用）。

- [ ] **Step 9: 提交**
```bash
git add engine/src/main/java/io/custos/engine/secrets broker/src/main/java/io/custos/broker/BrokerService.java engine/src/test/java/io/custos/engine/secrets/SecretsEngineRegistryTest.java
git commit -m "feat(engine): SecretsEngine SPI + registry; broker depends on interface"
```

---

## Task 2: custos-host 脚手架 + 配置绑定 + 启动期 sealed 引导

**Files:**
- Modify: `app/pom.xml`（加 web + broker + authz + nacos 依赖）
- Create: `app/src/main/java/io/custos/app/config/CustosProperties.java`
- Create: `app/src/main/java/io/custos/app/engine/EngineBootstrap.java`
- Test: `app/src/test/java/io/custos/app/EngineBootstrapTest.java`

> 沿用现 `app` 模块（artifactId 仍 `custos-app`），不新建模块名以免改动 reactor 顺序。

- [ ] **Step 1: app/pom.xml 增依赖**

在 `app/pom.xml` `<dependencies>` 加（保留现有 broker + spring-boot-starter）：
```xml
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId><version>3.3.2</version></dependency>
    <dependency><groupId>io.custos</groupId><artifactId>custos-authz</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>8.4.0</version></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><version>1.19.8</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>1.19.8</version><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><version>3.3.2</version><scope>test</scope></dependency>
```
并在 `app/pom.xml` 的 `<build>` 加 surefire/failsafe 钉 `api.version=1.40`（同 engine pom 的两段 plugin 配置，systemPropertyVariables `<api.version>1.40</api.version>`，failsafe 绑 integration-test+verify）。

- [ ] **Step 2: 写 CustosProperties**

`app/src/main/java/io/custos/app/config/CustosProperties.java`:
```java
package io.custos.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 application.yml 的 custos.* 配置。 */
@ConfigurationProperties(prefix = "custos")
public class CustosProperties {
    private Engine engine = new Engine();
    private Nacos nacos = new Nacos();
    private Identity identity = new Identity();
    private Broker broker = new Broker();

    public static class Engine {
        private String storageUrl = "jdbc:mysql://localhost:3306/custos";
        private String storageUsername = "custos";
        private String storagePassword = "custos";
        private int shares = 5;
        private int threshold = 3;
        // getters/setters
        public String getStorageUrl() { return storageUrl; } public void setStorageUrl(String v) { storageUrl = v; }
        public String getStorageUsername() { return storageUsername; } public void setStorageUsername(String v) { storageUsername = v; }
        public String getStoragePassword() { return storagePassword; } public void setStoragePassword(String v) { storagePassword = v; }
        public int getShares() { return shares; } public void setShares(int v) { shares = v; }
        public int getThreshold() { return threshold; } public void setThreshold(int v) { threshold = v; }
    }
    public static class Nacos {
        private String serverAddr = ""; private String namespace = "public"; private String policyDataId = "custos-policy"; private String group = "DEFAULT_GROUP";
        public String getServerAddr() { return serverAddr; } public void setServerAddr(String v) { serverAddr = v; }
        public String getNamespace() { return namespace; } public void setNamespace(String v) { namespace = v; }
        public String getPolicyDataId() { return policyDataId; } public void setPolicyDataId(String v) { policyDataId = v; }
        public String getGroup() { return group; } public void setGroup(String v) { group = v; }
    }
    public static class Identity {
        private String issuer = "custos";
        public String getIssuer() { return issuer; } public void setIssuer(String v) { issuer = v; }
    }
    public static class Broker {
        private String dbReadonlySchema = "appdb"; private String targetJdbcUrl = "jdbc:mysql://localhost:3306/appdb";
        public String getDbReadonlySchema() { return dbReadonlySchema; } public void setDbReadonlySchema(String v) { dbReadonlySchema = v; }
        public String getTargetJdbcUrl() { return targetJdbcUrl; } public void setTargetJdbcUrl(String v) { targetJdbcUrl = v; }
    }
    public Engine getEngine() { return engine; } public void setEngine(Engine v) { engine = v; }
    public Nacos getNacos() { return nacos; } public void setNacos(Nacos v) { nacos = v; }
    public Identity getIdentity() { return identity; } public void setIdentity(Identity v) { identity = v; }
    public Broker getBroker() { return broker; } public void setBroker(Broker v) { broker = v; }
}
```

- [ ] **Step 3: 写 EngineBootstrap 的失败测试**

`app/src/test/java/io/custos/app/EngineBootstrapTest.java`:
```java
package io.custos.app;

import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import io.custos.engine.seal.SealManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineBootstrapTest {
    @Test
    void buildsSealManagerStartingSealed() {
        CustosProperties props = new CustosProperties();
        props.getEngine().setStorageUrl("jdbc:mysql://localhost:3306/custos"); // 不连接，仅构建对象
        EngineBootstrap b = new EngineBootstrap(props);
        SealManager sm = b.sealManager();
        assertTrue(sm.status().sealed(), "启动应为 sealed");
    }
}
```
> 注：`status()` 在未 init 时 threshold=0、sealed=true（见 `DefaultSealManager.status`）；构建 DataSource/JSqlClient/JimmerSealStore 不触发连接，故无需 DB。

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -pl app -am test -Dtest=EngineBootstrapTest`
Expected: 编译失败（EngineBootstrap 未定义）。

- [ ] **Step 5: 写 EngineBootstrap**

`app/src/main/java/io/custos/app/engine/EngineBootstrap.java`:
```java
package io.custos.app.engine;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.app.config.CustosProperties;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import io.custos.engine.seal.DefaultSealManager;
import io.custos.engine.seal.JimmerSealStore;
import io.custos.engine.seal.SealManager;
import org.babyfish.jimmer.sql.JSqlClient;

import javax.sql.DataSource;

/** 启动期可建（无需密钥）：DataSource → JSqlClient → JimmerSealStore → SealManager（sealed）。 */
public final class EngineBootstrap {

    private final IntlSuite suite = new IntlSuite();
    private final DataSource dataSource;
    private final JSqlClient sql;
    private final SealManager sealManager;

    public EngineBootstrap(CustosProperties props) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(props.getEngine().getStorageUrl());
        ds.setUser(props.getEngine().getStorageUsername());
        ds.setPassword(props.getEngine().getStoragePassword());
        this.dataSource = ds;
        this.sql = JimmerClients.of(ds);
        this.sealManager = new DefaultSealManager(suite, new JimmerSealStore(sql));
    }

    public IntlSuite suite() { return suite; }
    public DataSource dataSource() { return dataSource; }
    public JSqlClient sql() { return sql; }
    public SealManager sealManager() { return sealManager; }
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -pl app -am test -Dtest=EngineBootstrapTest`
Expected: PASS。

- [ ] **Step 7: 提交**
```bash
git add app/pom.xml app/src/main/java/io/custos/app/config app/src/main/java/io/custos/app/engine app/src/test/java/io/custos/app/EngineBootstrapTest.java
git commit -m "feat(host): config binding + engine bootstrap (starts sealed)"
```

---

## Task 3: OperatorService（解封生命周期）+ REST admin + Bearer 保护

**Files:**
- Create: `app/src/main/java/io/custos/app/engine/UnsealedContext.java`
- Create: `app/src/main/java/io/custos/app/operator/OperatorService.java`
- Create: `app/src/main/java/io/custos/app/operator/OperatorController.java`
- Create: `app/src/main/java/io/custos/app/security/AdminTokenFilter.java`
- Create: `app/src/main/java/io/custos/app/config/HostConfig.java`
- Test: `app/src/test/java/io/custos/app/OperatorServiceTest.java`

- [ ] **Step 1: 写 UnsealedContext（解封后运营组件持有体）**

`app/src/main/java/io/custos/app/engine/UnsealedContext.java`:
```java
package io.custos.app.engine;

import io.custos.broker.BrokerService;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.storage.Storage;

/** 解封成功后装配的运营组件集合（依赖 keyring）。 */
public record UnsealedContext(Storage storage, AuditLog audit, BrokerService broker) {}
```

- [ ] **Step 2: 写 OperatorService 的失败测试（init→sealed；满阈值→unsealed 且装配 broker）**

`app/src/test/java/io/custos/app/OperatorServiceTest.java`:
```java
package io.custos.app;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import io.custos.app.operator.OperatorService;
import io.custos.authz.CasbinPdp;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OperatorServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private OperatorService op;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_audit (seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL, task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128), prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL, issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)");
        }
        CustosProperties props = new CustosProperties();
        props.getEngine().setStorageUrl(MYSQL.getJdbcUrl());
        props.getEngine().setStorageUsername("root");
        props.getEngine().setStoragePassword("root");
        var g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
        TokenService tokens = new JwtTokenService(g.generateKeyPair(), "custos", new InMemoryBlacklist());
        CasbinPdp pdp = new CasbinPdp();
        // admin 连接 + secrets engine 在 OperatorService 内按 props 装配；测试构造见实现签名
        op = new OperatorService(new EngineBootstrap(props), tokens, pdp, props, MYSQL.getJdbcUrl(), "root", "root");
    }

    @Test
    void initStaysSealedThenUnsealsAtThreshold() {
        assertTrue(op.status().sealed());
        List<String> shares = op.init(5, 3);     // base64 分片
        assertEquals(5, shares.size());
        assertTrue(op.status().sealed(), "init 后仍 sealed");
        op.unseal(shares.get(0));
        op.unseal(shares.get(1));
        var s = op.unseal(shares.get(2));
        assertFalse(s.sealed(), "满 3 片应解封");
        assertNotNull(op.unsealed(), "解封后应装配运营组件");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -pl app -am test -Dtest=OperatorServiceTest`
Expected: 编译失败（OperatorService 未定义）。

- [ ] **Step 4: 写 OperatorService（init/unseal/seal/status + 解封后装配）**

`app/src/main/java/io/custos/app/operator/OperatorService.java`:
```java
package io.custos.app.operator;

import io.custos.app.config.CustosProperties;
import io.custos.app.engine.EngineBootstrap;
import io.custos.app.engine.UnsealedContext;
import io.custos.authz.Pdp;
import io.custos.broker.BrokerService;
import io.custos.broker.SecretlessQueryExecutor;
import io.custos.engine.audit.HashChainAuditLog;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.seal.SealManager;
import io.custos.engine.seal.SealStatus;
import io.custos.engine.secrets.DynamicDbCredentials;
import io.custos.engine.storage.JimmerStorage;
import io.custos.engine.storage.Storage;
import io.custos.identity.TokenService;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 持有 SealManager 内存解封态；解封成功后装配依赖 keyring 的运营组件。 */
public final class OperatorService {

    private final EngineBootstrap engine;
    private final TokenService tokens;
    private final Pdp pdp;
    private final CustosProperties props;
    private final String adminJdbcUrl, adminUser, adminPwd;
    private final AtomicReference<UnsealedContext> ctx = new AtomicReference<>();

    public OperatorService(EngineBootstrap engine, TokenService tokens, Pdp pdp, CustosProperties props,
                           String adminJdbcUrl, String adminUser, String adminPwd) {
        this.engine = engine; this.tokens = tokens; this.pdp = pdp; this.props = props;
        this.adminJdbcUrl = adminJdbcUrl; this.adminUser = adminUser; this.adminPwd = adminPwd;
    }

    public List<String> init(int shares, int threshold) {
        List<byte[]> parts = engine.sealManager().init(shares, threshold);
        List<String> out = new ArrayList<>();
        for (byte[] p : parts) out.add(Base64.getEncoder().encodeToString(p));
        return out;
    }

    public SealStatus unseal(String shareB64) {
        SealStatus s = engine.sealManager().submitUnsealKey(Base64.getDecoder().decode(shareB64));
        if (!s.sealed() && ctx.get() == null) assemble();
        return s;
    }

    public void seal() {
        engine.sealManager().seal();
        ctx.set(null);
    }

    public SealStatus status() { return engine.sealManager().status(); }

    public UnsealedContext unsealed() {
        UnsealedContext c = ctx.get();
        if (c == null) throw new IllegalStateException("sealed");
        return c;
    }

    /** 解封后装配：Barrier(keyring) → Storage、AuditLog、BrokerService。 */
    private void assemble() {
        SealManager sm = engine.sealManager();
        Keyring keyring = ((io.custos.engine.seal.DefaultSealManager) sm).keyring();
        DefaultBarrier barrier = new DefaultBarrier(engine.suite(), keyring);
        Storage storage = new JimmerStorage(engine.sql(), barrier);
        // 审计密钥从 barrier 派生（稳定、不落明文）：HMAC(keyring 当前密钥, "audit")
        byte[] auditKey = engine.suite().hmac(barrier.seal("k".getBytes(StandardCharsets.UTF_8)), "audit".getBytes(StandardCharsets.UTF_8));
        HashChainAuditLog audit = new HashChainAuditLog(engine.sql(), engine.suite(), auditKey);
        try {
            Connection admin = DriverManager.getConnection(adminJdbcUrl, adminUser, adminPwd);
            DynamicDbCredentials creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(engine.sql()), props.getBroker().getTargetJdbcUrl());
            BrokerService broker = new BrokerService(tokens, pdp, creds, new SecretlessQueryExecutor(), props.getBroker().getTargetJdbcUrl());
            ctx.set(new UnsealedContext(storage, audit, broker));
        } catch (Exception e) {
            throw new IllegalStateException("assemble after unseal failed", e);
        }
    }
}
```
> auditKey 派生用 `hmac(barrier.seal(...), "audit")` 仅为获得一个稳定的、与解封态绑定的密钥材料；生产可换为从 keyring 显式派生专用审计密钥（路线图加固）。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -pl app -am test -Dtest=OperatorServiceTest`
Expected: PASS（init 后 sealed、满 3 片解封并装配）。

- [ ] **Step 6: 写 AdminTokenFilter + HostConfig（Bean 装配）+ OperatorController**

`app/src/main/java/io/custos/app/security/AdminTokenFilter.java`:
```java
package io.custos.app.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 简易 Bearer 保护：所有 /operator、/policy、/audit 路径需匹配 CUSTOS_ADMIN_TOKEN（环境变量）。 */
public final class AdminTokenFilter implements Filter {
    private final String expected;
    public AdminTokenFilter(String expected) { this.expected = expected; }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        String path = r.getRequestURI();
        boolean adminPath = path.startsWith("/operator") || path.startsWith("/policy") || path.startsWith("/audit");
        if (adminPath && (expected == null || expected.isBlank() || !("Bearer " + expected).equals(r.getHeader("Authorization")))) {
            ((HttpServletResponse) res).sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token required");
            return;
        }
        chain.doFilter(req, res);
    }
}
```

`app/src/main/java/io/custos/app/config/HostConfig.java`:
```java
package io.custos.app.config;

import io.custos.app.engine.EngineBootstrap;
import io.custos.app.operator.OperatorService;
import io.custos.app.security.AdminTokenFilter;
import io.custos.authz.CasbinPdp;
import io.custos.authz.ControlPlane;
import io.custos.authz.InMemoryControlPlane;
import io.custos.authz.NacosControlPlane;
import io.custos.authz.PolicyWatcher;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

@Configuration
@EnableConfigurationProperties(CustosProperties.class)
public class HostConfig {

    @Bean
    public EngineBootstrap engineBootstrap(CustosProperties props) { return new EngineBootstrap(props); }

    @Bean
    public TokenService tokenService(CustosProperties props) throws Exception {
        var g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
        return new JwtTokenService(g.generateKeyPair(), props.getIdentity().getIssuer(), new InMemoryBlacklist());
    }

    @Bean
    public CasbinPdp casbinPdp() { return new CasbinPdp(); }

    @Bean
    public ControlPlane controlPlane(CustosProperties props) {
        String addr = props.getNacos().getServerAddr();
        if (addr == null || addr.isBlank()) return new InMemoryControlPlane();
        return new NacosControlPlane(addr, props.getNacos().getNamespace(), props.getNacos().getGroup());
    }

    @Bean
    public PolicyWatcher policyWatcher(ControlPlane cp, CustosProperties props, CasbinPdp pdp) {
        PolicyWatcher w = new PolicyWatcher(cp, props.getNacos().getPolicyDataId(), pdp);
        w.start();
        return w;
    }

    @Bean
    public OperatorService operatorService(EngineBootstrap engine, TokenService tokens, CasbinPdp pdp, CustosProperties props) {
        return new OperatorService(engine, tokens, pdp, props,
                props.getEngine().getStorageUrl(), props.getEngine().getStorageUsername(), props.getEngine().getStoragePassword());
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
        FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(System.getenv("CUSTOS_ADMIN_TOKEN")));
        reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*");
        return reg;
    }
}
```

`app/src/main/java/io/custos/app/operator/OperatorController.java`:
```java
package io.custos.app.operator;

import io.custos.engine.seal.SealStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/operator")
public class OperatorController {
    private final OperatorService op;
    public OperatorController(OperatorService op) { this.op = op; }

    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody Map<String, Integer> body) {
        List<String> shares = op.init(body.getOrDefault("shares", 5), body.getOrDefault("threshold", 3));
        return Map.of("shares", shares);
    }

    @PostMapping("/unseal")
    public SealStatus unseal(@RequestBody Map<String, String> body) { return op.unseal(body.get("share")); }

    @PostMapping("/seal")
    public Map<String, Object> seal() { op.seal(); return Map.of("sealed", true); }

    @GetMapping("/status")
    public SealStatus status() { return op.status(); }
}
```

- [ ] **Step 7: 运行 app 编译 + OperatorServiceTest**

Run: `mvn -pl app -am test -Dtest=OperatorServiceTest`
Expected: PASS（控制器/过滤器/配置编译通过，OperatorServiceTest 仍绿）。

- [ ] **Step 8: 提交**
```bash
git add app/src/main/java/io/custos/app/engine/UnsealedContext.java app/src/main/java/io/custos/app/operator app/src/main/java/io/custos/app/security app/src/main/java/io/custos/app/config/HostConfig.java app/src/test/java/io/custos/app/OperatorServiceTest.java
git commit -m "feat(host): operator service (seal lifecycle) + REST admin + bearer filter"
```

---

## Task 4: PolicyService + AuditController + QueryController（secretless 查询面）

**Files:**
- Create: `app/src/main/java/io/custos/app/policy/PolicyService.java`
- Create: `app/src/main/java/io/custos/app/policy/PolicyController.java`
- Create: `app/src/main/java/io/custos/app/audit/AuditController.java`
- Create: `app/src/main/java/io/custos/app/query/QueryController.java`
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（加 PolicyService bean）

- [ ] **Step 1: 写 PolicyService + Controller**

`app/src/main/java/io/custos/app/policy/PolicyService.java`:
```java
package io.custos.app.policy;

import io.custos.app.config.CustosProperties;
import io.custos.authz.ControlPlane;

/** 把策略写入控制面（Nacos 或内存）。 */
public final class PolicyService {
    private final ControlPlane controlPlane;
    private final CustosProperties props;
    public PolicyService(ControlPlane controlPlane, CustosProperties props) { this.controlPlane = controlPlane; this.props = props; }
    public void put(String content) { controlPlane.publish(props.getNacos().getPolicyDataId(), content); }
}
```

`app/src/main/java/io/custos/app/policy/PolicyController.java`:
```java
package io.custos.app.policy;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/policy")
public class PolicyController {
    private final PolicyService svc;
    public PolicyController(PolicyService svc) { this.svc = svc; }

    @PostMapping
    public Map<String, Object> put(@RequestBody Map<String, String> body) {
        svc.put(body.get("content"));
        return Map.of("ok", true);
    }
}
```
在 `HostConfig` 加：
```java
    @Bean
    public PolicyService policyService(io.custos.authz.ControlPlane cp, CustosProperties props) { return new PolicyService(cp, props); }
```

- [ ] **Step 2: 写 AuditController（解封后查审计；未解封 409）**

`app/src/main/java/io/custos/app/audit/AuditController.java`:
```java
package io.custos.app.audit;

import io.custos.app.operator.OperatorService;
import io.custos.engine.audit.VerifyResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/audit")
public class AuditController {
    private final OperatorService op;
    public AuditController(OperatorService op) { this.op = op; }

    @GetMapping("/verify")
    public VerifyResult verify() {
        try { return op.unsealed().audit().verify(); }
        catch (IllegalStateException sealed) { throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed"); }
    }
}
```

- [ ] **Step 3: 写 QueryController（secretless；未解封 409）**

`app/src/main/java/io/custos/app/query/QueryController.java`:
```java
package io.custos.app.query;

import io.custos.app.operator.OperatorService;
import io.custos.broker.QueryIntent;
import io.custos.broker.QueryResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class QueryController {
    private final OperatorService op;
    public QueryController(OperatorService op) { this.op = op; }

    @PostMapping("/query_db")
    public QueryResult query(@RequestBody Map<String, String> body) {
        try {
            return op.unsealed().broker().queryDb(
                    new QueryIntent(body.get("tool"), body.get("schema"), body.get("sql")),
                    body.get("userToken"));
        } catch (IllegalStateException sealed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed");
        }
    }
}
```
> `/query_db` 不在 admin Filter 路径内（它对 agent 开放，靠 userToken 鉴权）。

- [ ] **Step 4: 编译验证**

Run: `mvn -pl app -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**
```bash
git add app/src/main/java/io/custos/app/policy app/src/main/java/io/custos/app/audit app/src/main/java/io/custos/app/query app/src/main/java/io/custos/app/config/HostConfig.java
git commit -m "feat(host): policy/audit/query_db endpoints (sealed->409, secretless)"
```

---

## Task 5: MCP stdio transport runner + custos-cli

**Files:**
- Create: `app/src/main/java/io/custos/app/mcp/McpStdioRunner.java`
- Modify: `pom.xml`（modules 加 `cli`）
- Create: `cli/pom.xml`、`cli/src/main/java/io/custos/cli/CustosCli.java`

- [ ] **Step 1: 写 McpStdioRunner（按属性启动，默认关）**

`app/src/main/java/io/custos/app/mcp/McpStdioRunner.java`:
```java
package io.custos.app.mcp;

import io.custos.app.operator.OperatorService;
import io.custos.broker.McpQueryToolServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** custos.transport.mcp-stdio=true 时，把 query_db 经 MCP stdio 暴露（需已解封）。 */
@Component
public class McpStdioRunner implements CommandLineRunner {
    private final OperatorService op;
    @Value("${custos.transport.mcp-stdio:false}") boolean enabled;
    public McpStdioRunner(OperatorService op) { this.op = op; }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        new McpQueryToolServer(op.unsealed().broker()).start();   // 需启动前已解封（stdio 模式）
    }
}
```
> stdio 模式假定容器以"已注入解封"的运维方式启动；REST 模式（默认）不开 MCP stdio，query_db 走 `/query_db`。两种 transport 互不强依赖。

- [ ] **Step 2: modules 加 cli + 写 cli/pom.xml**

`pom.xml` `<modules>` 追加 `<module>cli</module>`。
`cli/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.custos</groupId><artifactId>custos-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>custos-cli</artifactId>
  <dependencies>
    <dependency><groupId>info.picocli</groupId><artifactId>picocli</artifactId><version>4.7.6</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: 写 CustosCli（operator/policy/audit 打 REST admin，用 JDK HttpClient）**

`cli/src/main/java/io/custos/cli/CustosCli.java`:
```java
package io.custos.cli;

import picocli.CommandLine;
import picocli.CommandLine.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Command(name = "custos", subcommands = {CustosCli.Operator.class, CustosCli.Policy.class, CustosCli.Audit.class})
public class CustosCli {
    @Option(names = "--server", defaultValue = "http://127.0.0.1:8080") static String server;
    @Option(names = "--token", defaultValue = "") static String token;

    static String post(String path, String json) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(server + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
    static String get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(server + path)).header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Command(name = "operator", subcommands = {Operator.Init.class, Operator.Unseal.class, Operator.Status.class})
    static class Operator {
        @Command(name = "init") static class Init implements Runnable {
            @Option(names = "--shares", defaultValue = "5") int shares;
            @Option(names = "--threshold", defaultValue = "3") int threshold;
            public void run() { try { System.out.println(post("/operator/init", "{\"shares\":" + shares + ",\"threshold\":" + threshold + "}")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
        @Command(name = "unseal") static class Unseal implements Runnable {
            @Parameters(index = "0") String share;
            public void run() { try { System.out.println(post("/operator/unseal", "{\"share\":\"" + share + "\"}")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
        @Command(name = "status") static class Status implements Runnable {
            public void run() { try { System.out.println(get("/operator/status")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
    }
    @Command(name = "policy") static class Policy implements Runnable {
        @Option(names = "--content", required = true) String content;
        public void run() { try { System.out.println(post("/policy", "{\"content\":" + jsonStr(content) + "}")); } catch (Exception e) { throw new RuntimeException(e); } }
    }
    @Command(name = "audit") static class Audit {
        @Command(name = "verify") static class Verify implements Runnable {
            public void run() { try { System.out.println(get("/audit/verify")); } catch (Exception e) { throw new RuntimeException(e); } }
        }
    }
    static String jsonStr(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    public static void main(String[] args) { System.exit(new CommandLine(new CustosCli()).execute(args)); }
}
```
> `policy` 的子命令 audit verify：picocli 中 `Audit` 需声明 subcommands；修正为 `@Command(name="audit", subcommands=Audit.Verify.class)`。实现时在 `Audit` 类上加 `subcommands = {Audit.Verify.class}`，并在顶层 `subcommands` 列表保持 `Audit.class`。

- [ ] **Step 4: 编译 cli + 全工程**

Run: `mvn -N install -Dmaven.test.skip=true && mvn -pl app,cli -am -DskipTests package`
Expected: BUILD SUCCESS（cli jar + app 可执行 jar）。

- [ ] **Step 5: 提交**
```bash
git add pom.xml app/src/main/java/io/custos/app/mcp cli
git commit -m "feat(cli,host): picocli admin CLI + optional MCP stdio transport"
```

---

## Task 6: 端到端宿主集成测试 + docker-compose 真跑 + demo.md 真命令

**Files:**
- Test: `app/src/test/java/io/custos/app/HostEndToEndIT.java`
- Modify: `app/src/main/resources/application.yml`（补 server.port、custos.broker.target-jdbc-url 等）
- Modify: `examples/docker-compose.yml`（custos 服务加 CUSTOS_ADMIN_TOKEN、暴露 8080）
- Modify: `examples/demo.md`（真命令）

- [ ] **Step 1: 写端到端 IT（@SpringBootTest + Testcontainers）**

`app/src/test/java/io/custos/app/HostEndToEndIT.java`:
```java
package io.custos.app;

import io.custos.app.operator.OperatorService;
import io.custos.app.policy.PolicyService;
import io.custos.broker.QueryIntent;
import io.custos.broker.QueryResult;
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
        r.add("custos.broker.target-jdbc-url", HostEndToEndIT::appdbUrl);
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
        // AC1：启动 sealed，未解封查询被拒
        assertTrue(op.status().sealed());
        assertThrows(IllegalStateException.class, () -> op.unsealed());

        // 解封
        List<String> shares = op.init(5, 3);
        op.unseal(shares.get(0)); op.unseal(shares.get(1));
        assertFalse(op.unseal(shares.get(2)).sealed());

        // 写策略（允许 claude-prod 只读）
        policy.put("p, role:reader, tool:db/*, read, allow\ng, agent:claude-prod, role:reader\n");

        // 准：claude-prod 查询返回行，且 secretless
        String allowTok = tokens.issue(new AgentId("corp.example", "claude-prod", "s1"), Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
        QueryResult ok = op.unsealed().broker().queryDb(new QueryIntent("db/query_orders", "appdb", "SELECT COUNT(*) AS n FROM appdb.orders"), allowTok);
        assertTrue(ok.allowed());
        assertEquals(3L, ((Number) ok.rows().get(0).get("n")).longValue());
        assertFalse(ok.toString().contains("v_ro_"));

        // 拒：evil-agent
        String denyTok = tokens.issue(new AgentId("corp.example", "evil-agent", "s1"), Set.of("x"), "broker", Duration.ofMinutes(15)).jwt();
        QueryResult denied = op.unsealed().broker().queryDb(new QueryIntent("db/query_orders", "appdb", "SELECT 1"), denyTok);
        assertFalse(denied.allowed());

        // 审计可校验（AC7 正路径）
        assertTrue(op.unsealed().audit().verify().ok());
    }
}
```

- [ ] **Step 2: 补 application.yml（连字符命名匹配 relaxed binding）**

`app/src/main/resources/application.yml` 改为：
```yaml
server:
  port: 8080
custos:
  engine:
    storage-url: jdbc:mysql://mysql:3306/custos
    storage-username: custos
    storage-password: ${CUSTOS_DB_PWD:custos}
    shares: 5
    threshold: 3
  nacos:
    server-addr: ${NACOS_ADDR:}
    namespace: public
    policy-data-id: custos-policy
    group: DEFAULT_GROUP
  identity:
    issuer: custos
  broker:
    db-readonly-schema: appdb
    target-jdbc-url: jdbc:mysql://mysql:3306/appdb
  transport:
    mcp-stdio: false
```

- [ ] **Step 3: 运行端到端 IT**

Run: `mvn -pl app -am test -Dtest=HostEndToEndIT`
Expected: PASS（sealed→解封→策略→准/拒→审计 verify 全链路）。

- [ ] **Step 4: docker-compose 加 admin token + 端口；校验**

`examples/docker-compose.yml` 的 `custos` 服务 environment 加 `CUSTOS_ADMIN_TOKEN: demo-token`，并加 `ports: ["8080:8080"]`。
Run: `docker compose -f examples/docker-compose.yml config`
Expected: 输出合并配置，无错误。

- [ ] **Step 5: 更新 demo.md 为真命令**

把 `examples/demo.md` 中占位的 `custos operator/policy/audit` 段替换为真命令（二选一）：
````markdown
## 1. 解封（AC1）
```bash
TOKEN=demo-token
custos --token $TOKEN operator init --shares 5 --threshold 3   # 输出 5 个 base64 分片
custos --token $TOKEN operator unseal <share1>
custos --token $TOKEN operator unseal <share2>
custos --token $TOKEN operator unseal <share3>
custos --token $TOKEN operator status                          # {"sealed":false,...}
# 或直接 curl：
# curl -s -XPOST localhost:8080/operator/init -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"shares":5,"threshold":3}'
```
## 2. 写策略
```bash
custos --token $TOKEN policy --content 'p, role:reader, tool:db/*, read, allow
g, agent:claude-prod, role:reader'
```
## 3. 查询（AC3/4/5）
```bash
curl -s -XPOST localhost:8080/query_db -H 'Content-Type: application/json' \
  -d '{"tool":"db/query_orders","schema":"appdb","sql":"SELECT COUNT(*) AS n FROM appdb.orders","userToken":"<JWT>"}'
# 期望 {"allowed":true,"rows":[{"n":3}],...}
```
## 5. 审计（AC7）
```bash
custos --token $TOKEN audit verify         # {"ok":true,...}
```
````
（保留原 AC2/AC6/AC8 段落；秒级吊销 AC6 用 `policy --content '...deny...'` 后重发 query 验证。）

- [ ] **Step 6: 全工程 verify**

Run: `mvn -B verify`
Expected: 全模块 BUILD SUCCESS（含 HostEndToEndIT）。

- [ ] **Step 7: 提交**
```bash
git add app/src/test/java/io/custos/app/HostEndToEndIT.java app/src/main/resources/application.yml examples/docker-compose.yml examples/demo.md
git commit -m "test(host): end-to-end seal->policy->query->audit IT; demo runnable with real commands"
```

---

## Self-Review（对照生产架构 spec §8）

- **范围覆盖**：①SPI 正式化(SecretsEngine+Registry)→Task 1；②custos-host(配置+引导+sealed)→Task 2；③OperatorService+REST admin+Bearer→Task 3；④policy/audit/query_db 端点→Task 4；⑤MCP transport + custos-cli→Task 5；⑥端到端 IT + compose + demo→Task 6。全覆盖。
- **解封生命周期**：Task 2 建 sealed 的 SealManager（仅 DataSource）；Task 3 在 unseal 满阈值后 `assemble()` 建 Barrier/Storage/Audit/Broker 并存 AtomicReference；未解封 `unsealed()` 抛 IllegalStateException → 控制器转 409。一致。
- **类型一致性**：`SecretsEngine.issue/revoke/type`、`SecretsEngineRegistry.mount/require`、`OperatorService.init(List<String>)/unseal(SealStatus)/seal/status/unsealed(UnsealedContext)`、`UnsealedContext(storage,audit,broker)`、`BrokerService(TokenService,Pdp,SecretsEngine,SecretlessQueryExecutor,String)` 跨任务一致。Base64 分片在 CLI/REST 与 OperatorService 间一致。
- **占位扫描**：Task 5 picocli 的 `Audit` 子命令注册已在注记中明确修正写法（`@Command(name="audit", subcommands={Audit.Verify.class})`）——实现时照此，非占位。
- **依赖/Docker 约定**：跨模块测试前 `mvn -N install` + 装 engine/identity/authz/broker；含 IT 模块 pom 钉 `api.version=1.40`（app pom 在 Task 2 Step 1 已加）。
- **解耦铁律**：engine 仍无 Spring（SecretsEngine SPI 是纯接口）；host 依赖各层接口并组合；query_db 只回 QueryResult（无凭证）。一致。

> **下一子项目**：P-OBO / P-ABAC / P-AKSK（各自 brainstorming→spec→plan）。
