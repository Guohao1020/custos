# v0.5 资源接入（Resource Onboarding · db.relational）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员运行时经 REST/CLI 注册关系型数据库资源（含高权限凭证），custos 据此现场签发即用即焚只读凭证、全程 secretless，高权限凭证经 Barrier 加密托管。

**Architecture:** 新包 `engine/resource`：`ResourceRecord`/`RoleDef` 数据 → `ResourceStore`（经现有 Barrier 加密 `Storage` 持久化为 `resource/<name>` KV blob）→ `CredentialAdapter`（MySQL/Postgres 内置 + Template 逃生口）→ `DbDynamicEngine implements SecretsEngine`（开 admin 连接→选适配器→签发→Zeroize→登记租约）→ `ResourceManager`（注册/列表/轮换 + 挂进 `SecretsEngineRegistry` + 审计）。app 加 `/resources` REST + 装配时载入挂载；broker 按 resource/role 解析；去掉硬编码 `target-jdbc-url`。

**Tech Stack:** Java 21 · Maven。零新增依赖：复用 `Storage`(Barrier 加密 KV)、Jackson(jackson-databind)、`SecretsEngine`/`SecretsEngineRegistry`/`IssuedCred`/`LeaseManager`/`Zeroize`/`HashChainAuditLog`、mysql-connector-j 8.4.0 + postgresql 42.7.3、Spring Boot Web 3.3.2、picocli 4.7.6、Testcontainers 1.19.8（`api.version=1.40`）。

**前置：** 分支 `impl/m15-resource-onboarding`（已建，spec 在此）。仅实现 `db.relational` 动态型；`StaticSecretEngine`/LLM/MQ/NoSQL 不写（spec §9）。

**红线：** 高权限密码只存在 Barrier 密文里；仅 issue/revoke/rotate 内存解出、用后 `Zeroize`；从不进 IssuedCred、REST 响应、日志、Nacos。标识符仅 `[0-9a-f]` 防注入（沿用现有动态凭证做法）。

---

### Task 1: ResourceRecord + RoleDef 数据模型

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/RoleKind.java`
- Create: `engine/src/main/java/io/custos/engine/resource/RoleDef.java`
- Create: `engine/src/main/java/io/custos/engine/resource/ResourceRecord.java`
- Test: `engine/src/test/java/io/custos/engine/resource/ResourceRecordTest.java`

- [ ] **Step 1: 写失败测试（Jackson 往返）**

```java
package io.custos.engine.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRecordTest {
    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ResourceRecord r = new ResourceRecord("appdb", "db.relational", "mysql",
                "jdbc:mysql://localhost:3306/appdb", "custos", "custospwd",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, "appdb")));
        byte[] json = om.writeValueAsBytes(r);
        ResourceRecord back = om.readValue(json, ResourceRecord.class);
        assertEquals(r, back);
        assertEquals("custospwd", back.adminPassword());
        assertEquals(RoleKind.BUILTIN_READONLY, back.roles().get(0).kind());
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — Run: `mvn -pl engine test -Dtest=ResourceRecordTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败（类不存在）

- [ ] **Step 3: 写实现**

```java
// RoleKind.java
package io.custos.engine.resource;
/** 角色签发方式：内置只读适配器，或管理员自填 SQL 模板。 */
public enum RoleKind { BUILTIN_READONLY, TEMPLATE }
```

```java
// RoleDef.java
package io.custos.engine.resource;
import java.util.List;
/**
 * 一个资源下的具名签发角色。
 * BUILTIN_READONLY：按资源 dialect 选内置适配器，creationStatements/revocationStatements 留空。
 * TEMPLATE：管理员自填 SQL，占位符 {{name}}/{{password}}/{{expiration}}。
 */
public record RoleDef(String name, RoleKind kind,
                      List<String> creationStatements, List<String> revocationStatements,
                      long defaultTtlSeconds, String schema) {}
```

```java
// ResourceRecord.java
package io.custos.engine.resource;
import java.util.List;
/**
 * 受治理后端的注册条目。adminPassword 为高权限凭证——整条记录经 Barrier 加密落盘，
 * 该字段从不出现在 REST 响应/日志/IssuedCred 中。
 * type 为开放分类（v0.5 仅 db.relational）；dialect ∈ {mysql, postgresql}（template 角色不依赖 dialect）。
 */
public record ResourceRecord(String name, String type, String dialect,
                             String jdbcUrl, String adminUsername, String adminPassword,
                             List<RoleDef> roles) {
    public RoleDef role(String roleName) {
        return roles.stream().filter(r -> r.name().equals(roleName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no role '" + roleName + "' on resource '" + name + "'"));
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — Run: `mvn -pl engine test -Dtest=ResourceRecordTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/ engine/src/test/java/io/custos/engine/resource/ResourceRecordTest.java
git commit -m "feat(engine): ResourceRecord + RoleDef resource model"
```

---

### Task 2: ResourceStore（Barrier 加密 KV 持久化）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/ResourceStore.java`
- Test: `engine/src/test/java/io/custos/engine/resource/ResourceStoreTest.java`（in-memory Storage 假实现，纯单元）
- Test: `engine/src/test/java/io/custos/engine/resource/ResourceStoreIT.java`（JimmerStorage 真 Barrier，断言落盘无明文密码）

- [ ] **Step 1: 写失败单测**

```java
package io.custos.engine.resource;

import io.custos.engine.storage.Storage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStoreTest {
    /** 内存 Storage 假实现（不加密，仅验 ResourceStore 的序列化/键命名/列表/删除）。 */
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new LinkedHashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String prefix) { return m.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList(); }
    }

    private ResourceRecord rec(String name) {
        return new ResourceRecord(name, "db.relational", "mysql",
                "jdbc:mysql://h/" + name, "admin", "pwd",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, name)));
    }

    @Test
    void putGetListDelete() {
        ResourceStore store = new ResourceStore(new MemStorage());
        store.put(rec("appdb"));
        store.put(rec("orders"));
        assertEquals("admin", store.get("appdb").orElseThrow().adminUsername());
        assertEquals(List.of("appdb", "orders"), store.listNames());
        store.delete("appdb");
        assertTrue(store.get("appdb").isEmpty());
        assertEquals(List.of("orders"), store.listNames());
    }
}
```

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=ResourceStoreTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**

```java
package io.custos.engine.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.custos.engine.storage.Storage;

import java.util.List;
import java.util.Optional;

/** 资源记录持久化：序列化为 JSON 交给（Barrier 加密的）Storage，key=resource/<name>。 */
public final class ResourceStore {
    private static final String PREFIX = "resource/";
    private static final ObjectMapper OM = new ObjectMapper();
    private final Storage storage;

    public ResourceStore(Storage storage) { this.storage = storage; }

    public void put(ResourceRecord r) {
        try {
            storage.put(PREFIX + r.name(), OM.writeValueAsBytes(r));
        } catch (Exception e) {
            throw new IllegalStateException("serialize resource failed: " + r.name(), e);
        }
    }

    public Optional<ResourceRecord> get(String name) {
        return storage.get(PREFIX + name).map(bytes -> {
            try { return OM.readValue(bytes, ResourceRecord.class); }
            catch (Exception e) { throw new IllegalStateException("deserialize resource failed: " + name, e); }
        });
    }

    public List<String> listNames() {
        return storage.list(PREFIX).stream().map(k -> k.substring(PREFIX.length())).toList();
    }

    public void delete(String name) { storage.delete(PREFIX + name); }
}
```

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl engine test -Dtest=ResourceStoreTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: 写密钥托管 IT**（参照现有 `engine/src/test/java/io/custos/engine/storage/JimmerStorageIT.java` 的 Testcontainers + Barrier + JimmerStorage 装配；读它取容器/建表/Barrier 构造样板）

```java
package io.custos.engine.resource;

import io.custos.engine.storage.JimmerStorage;
import io.custos.engine.storage.Storage;
// ... 其余 import 照搬 JimmerStorageIT（MySQLContainer、Barrier、JimmerClients、Keyring 等）

@org.testcontainers.junit.jupiter.Testcontainers
class ResourceStoreIT {
    // @Container MySQLContainer + @BeforeEach 建 custos_storage 表 + 装配 DefaultBarrier(IntlSuite, keyring)
    // 与 JimmerStorageIT 完全相同的装配样板（直接照搬其 setUp）

    @org.junit.jupiter.api.Test
    void adminPasswordNeverPlaintextOnDisk() throws Exception {
        Storage storage = new JimmerStorage(sql, barrier);   // sql/barrier 来自 setUp
        ResourceStore store = new ResourceStore(storage);
        store.put(new ResourceRecord("appdb", "db.relational", "mysql",
                "jdbc:mysql://h/appdb", "custos", "SuperSecretAdminPwd123",
                java.util.List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY,
                        java.util.List.of(), java.util.List.of(), 3600, "appdb"))));
        // 往返可读
        org.junit.jupiter.api.Assertions.assertEquals("SuperSecretAdminPwd123",
                store.get("appdb").orElseThrow().adminPassword());
        // 直读 custos_storage 原始字节：必须是密文，grep 不到明文密码
        try (var st = adminConn.createStatement();
             var rs = st.executeQuery("SELECT HEX(svalue) FROM custos_storage WHERE skey='resource/appdb'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rs.next());
            String hex = rs.getString(1);
            String pl21 = io.custos.engine.crypto.IntlSuite.class != null
                    ? bytesToHex("SuperSecretAdminPwd123".getBytes(java.nio.charset.StandardCharsets.UTF_8)) : "";
            org.junit.jupiter.api.Assertions.assertFalse(hex.contains(pl21), "高权限密码明文落盘了！");
        }
    }
    private static String bytesToHex(byte[] b) { return java.util.HexFormat.of().formatHex(b).toUpperCase(); }
}
```
> 实现者：照搬 `JimmerStorageIT` 的 `@Container`/`setUp`（含 `custos_storage` 建表、`adminConn`、`sql`、`barrier`、`keyring`）。本 IT 只新增上面这个断言方法。

- [ ] **Step 6: 跑 IT（需 Docker）** — Run: `mvn -pl engine test -Dtest=ResourceStoreIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS（密文落盘、无明文）

- [ ] **Step 7: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/ResourceStore.java engine/src/test/java/io/custos/engine/resource/ResourceStoreTest.java engine/src/test/java/io/custos/engine/resource/ResourceStoreIT.java
git commit -m "feat(engine): ResourceStore — Barrier-encrypted resource persistence"
```

---

### Task 3: CredentialAdapter SPI + MySqlAdapter

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/MintedCred.java`
- Create: `engine/src/main/java/io/custos/engine/resource/CredentialAdapter.java`
- Create: `engine/src/main/java/io/custos/engine/resource/MySqlAdapter.java`
- Test: `engine/src/test/java/io/custos/engine/resource/MySqlAdapterIT.java`

- [ ] **Step 1: 写失败 IT**（Testcontainers MySQL；参照现有 `engine/.../secrets/DynamicDbCredentialsIT.java` 的容器/admin 连接样板）

```java
package io.custos.engine.resource;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MySqlAdapterIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");
    Connection admin;
    @BeforeEach void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1),(2)");
        }
    }
    private RoleDef readOnly() { return new RoleDef("read-only", RoleKind.BUILTIN_READONLY, java.util.List.of(), java.util.List.of(), 3600, "appdb"); }

    @Test void issueThenRevoke() throws Exception {
        MySqlAdapter a = new MySqlAdapter();
        MintedCred c = a.issue(admin, readOnly(), Duration.ofMinutes(5));
        assertTrue(c.username().startsWith("v_ro_"));
        // 用签出的凭证能 SELECT
        String url = MYSQL.getJdbcUrl().replaceFirst("/\\w+(\\?|$)", "/appdb$1");
        try (Connection u = DriverManager.getConnection(url, c.username(), c.password());
             ResultSet rs = u.createStatement().executeQuery("SELECT COUNT(*) FROM appdb.orders")) {
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1));
        }
        a.revoke(admin, c.username(), readOnly());
        // 撤销后账号没了
        try (ResultSet rs = admin.createStatement().executeQuery(
                "SELECT COUNT(*) FROM mysql.user WHERE user='" + c.username() + "'")) {
            assertTrue(rs.next()); assertEquals(0, rs.getInt(1));
        }
    }
}
```

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=MySqlAdapterIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**

```java
// MintedCred.java
package io.custos.engine.resource;
/** 适配器现场签发的裸凭证（无 lease，由引擎登记租约）。 */
public record MintedCred(String username, String password) {}
```

```java
// CredentialAdapter.java
package io.custos.engine.resource;
import java.sql.Connection;
import java.time.Duration;
/** 动态凭证型方言适配 SPI：用 admin 连接现场签发/撤销一个短时账号。 */
public interface CredentialAdapter {
    MintedCred issue(Connection admin, RoleDef role, Duration ttl);
    void revoke(Connection admin, String username, RoleDef role);
}
```

```java
// MySqlAdapter.java —— DDL 移植自 engine/.../secrets/DynamicDbCredentials.java
package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/** MySQL 内置只读适配器：CREATE USER + GRANT SELECT；撤销 DROP USER。标识符仅 [0-9a-f] 防注入。 */
public final class MySqlAdapter implements CredentialAdapter {
    private final SecureRandom random = new SecureRandom();

    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + pwd + "'");
            st.execute("GRANT SELECT ON `" + role.schema() + "`.* TO '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("mysql issue failed", e);
        }
        return new MintedCred(user, pwd);
    }

    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            st.execute("DROP USER IF EXISTS '" + username + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("mysql revoke failed: " + username, e);
        }
    }

    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
```

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl engine test -Dtest=MySqlAdapterIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/MintedCred.java engine/src/main/java/io/custos/engine/resource/CredentialAdapter.java engine/src/main/java/io/custos/engine/resource/MySqlAdapter.java engine/src/test/java/io/custos/engine/resource/MySqlAdapterIT.java
git commit -m "feat(engine): CredentialAdapter SPI + MySqlAdapter"
```

---

### Task 4: PostgresAdapter

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/PostgresAdapter.java`
- Test: `engine/src/test/java/io/custos/engine/resource/PostgresAdapterIT.java`（Testcontainers PostgreSQL；参照现有 `PostgresDynamicCredentialsIT.java`）

- [ ] **Step 1: 写失败 IT** —— 结构同 Task 3 的 MySqlAdapterIT，但用 `PostgreSQLContainer<>("postgres:16")`，建表 `CREATE TABLE orders(id INT)`，用 `PostgresAdapter`，断言签出凭证能 SELECT、revoke 后 `SELECT COUNT(*) FROM pg_roles WHERE rolname=?` 为 0。（容器/连接样板照搬 `engine/src/test/java/io/custos/engine/secrets/PostgresDynamicCredentialsIT.java`。）

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=PostgresAdapterIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现** —— DDL 移植自 `engine/.../secrets/PostgresDynamicCredentials.java`：

```java
package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/** PostgreSQL 内置只读适配器：CREATE ROLE LOGIN + GRANT USAGE/SELECT；撤销 REASSIGN/DROP OWNED/DROP ROLE。 */
public final class PostgresAdapter implements CredentialAdapter {
    private final SecureRandom random = new SecureRandom();

    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + user + " LOGIN PASSWORD '" + pwd + "'");
            st.execute("GRANT USAGE ON SCHEMA " + role.schema() + " TO " + user);
            st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + role.schema() + " TO " + user);
        } catch (Exception e) {
            throw new IllegalStateException("postgres issue failed", e);
        }
        return new MintedCred(user, pwd);
    }

    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            st.execute("REASSIGN OWNED BY " + username + " TO CURRENT_USER");
            st.execute("DROP OWNED BY " + username);
            st.execute("DROP ROLE IF EXISTS " + username);
        } catch (Exception e) {
            throw new IllegalStateException("postgres revoke failed: " + username, e);
        }
    }

    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
```

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl engine test -Dtest=PostgresAdapterIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/PostgresAdapter.java engine/src/test/java/io/custos/engine/resource/PostgresAdapterIT.java
git commit -m "feat(engine): PostgresAdapter"
```

---

### Task 5: TemplateAdapter（SQL 模板逃生口）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/TemplateAdapter.java`
- Test: `engine/src/test/java/io/custos/engine/resource/TemplateAdapterTest.java`（占位符替换纯单元）
- Test: `engine/src/test/java/io/custos/engine/resource/TemplateAdapterIT.java`（MySQL 跑真模板）

- [ ] **Step 1: 写失败单测（占位符替换）**

```java
package io.custos.engine.resource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemplateAdapterTest {
    @Test void substitutesPlaceholders() {
        String out = TemplateAdapter.render(
                "CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'",
                "v_ro_abc", "deadbeef", "2026-01-01 00:00:00");
        assertEquals("CREATE USER 'v_ro_abc'@'%' IDENTIFIED BY 'deadbeef'", out);
    }
}
```

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=TemplateAdapterTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**

```java
package io.custos.engine.resource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** 模板逃生口：跑 RoleDef 的 creation/revocation 语句，占位符 {{name}}/{{password}}/{{expiration}}。 */
public final class TemplateAdapter implements CredentialAdapter {
    private static final DateTimeFormatter EXP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private final SecureRandom random = new SecureRandom();

    static String render(String tpl, String name, String password, String expiration) {
        return tpl.replace("{{name}}", name).replace("{{password}}", password).replace("{{expiration}}", expiration);
    }

    @Override
    public MintedCred issue(Connection admin, RoleDef role, Duration ttl) {
        String user = "v_ro_" + hex(6);
        String pwd = hex(16);
        String exp = EXP.format(Instant.now().plus(ttl));
        try (Statement st = admin.createStatement()) {
            for (String s : role.creationStatements()) st.execute(render(s, user, pwd, exp));
        } catch (Exception e) {
            throw new IllegalStateException("template issue failed", e);
        }
        return new MintedCred(user, pwd);
    }

    @Override
    public void revoke(Connection admin, String username, RoleDef role) {
        try (Statement st = admin.createStatement()) {
            for (String s : role.revocationStatements()) st.execute(render(s, username, "", ""));
        } catch (Exception e) {
            throw new IllegalStateException("template revoke failed: " + username, e);
        }
    }

    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
```

- [ ] **Step 4: 跑单测确认通过** — Run: `mvn -pl engine test -Dtest=TemplateAdapterTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: 写 IT**（MySQL 容器，role 用 TEMPLATE，creationStatements=`["CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'", "GRANT SELECT ON `appdb`.* TO '{{name}}'@'%'"]`，revocationStatements=`["DROP USER IF EXISTS '{{name}}'@'%'"]`；断言签出可 SELECT、revoke 后账号没了。容器样板同 Task 3。）

- [ ] **Step 6: 跑 IT 确认通过** — Run: `mvn -pl engine test -Dtest=TemplateAdapterIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 7: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/TemplateAdapter.java engine/src/test/java/io/custos/engine/resource/TemplateAdapterTest.java engine/src/test/java/io/custos/engine/resource/TemplateAdapterIT.java
git commit -m "feat(engine): TemplateAdapter — SQL template escape hatch"
```

---

### Task 6: DbDynamicEngine（implements SecretsEngine）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/DbDynamicEngine.java`
- Test: `engine/src/test/java/io/custos/engine/resource/DbDynamicEngineIT.java`（MySQL + 真 ResourceStore + LeaseManager）

- [ ] **Step 1: 写失败 IT** —— 装配：MySQL 容器 + JimmerStorage(barrier) + ResourceStore + DefaultLeaseManager(sql)。注册一条 mysql 资源（admin=root/root，role read-only，schema appdb），构造 `DbDynamicEngine(record, store, leases)`，`issue("read-only", ttl)` → 用返回 IssuedCred 连库 SELECT 成功 → `revoke(leaseId)` → 账号 DROP。再断言 `engine.jdbcUrl()` 等于资源 jdbcUrl，且 `IssuedCred.toString()` 不含 admin 密码 `root`。（容器/Barrier 样板照搬 JimmerStorageIT + Task 3。）

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=DbDynamicEngineIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**

```java
package io.custos.engine.resource;

import io.custos.engine.crypto.Zeroize;
import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngine;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

/**
 * 动态凭证型引擎：包一条 ResourceRecord。issue 时临时开 admin 连接（高权限密码内存解出、用后 Zeroize、关连接），
 * 按 role.kind/dialect 选适配器现场签发，登记租约（到期 revoker 撤销）。
 */
public final class DbDynamicEngine implements SecretsEngine {
    private final ResourceStore store;
    private final LeaseManager leases;
    private final String resourceName;

    public DbDynamicEngine(String resourceName, ResourceStore store, LeaseManager leases) {
        this.resourceName = resourceName;
        this.store = store;
        this.leases = leases;
    }

    @Override public String type() { return "db.relational"; }

    /** 给 broker 用：本资源的连接串（执行器用签出的只读凭证连它）。 */
    public String jdbcUrl() { return record().jdbcUrl(); }

    private ResourceRecord record() {
        return store.get(resourceName).orElseThrow(() -> new IllegalStateException("resource gone: " + resourceName));
    }

    private CredentialAdapter adapterFor(ResourceRecord r, RoleDef role) {
        if (role.kind() == RoleKind.TEMPLATE) return new TemplateAdapter();
        return switch (r.dialect()) {
            case "mysql" -> new MySqlAdapter();
            case "postgresql" -> new PostgresAdapter();
            default -> throw new IllegalArgumentException("no builtin adapter for dialect: " + r.dialect());
        };
    }

    @Override
    public IssuedCred issue(String roleName, Duration ttl) {
        ResourceRecord r = record();
        RoleDef role = r.role(roleName);
        byte[] pwd = r.adminPassword().getBytes(StandardCharsets.UTF_8);
        MintedCred minted;
        try (Connection admin = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), new String(pwd, StandardCharsets.UTF_8))) {
            minted = adapterFor(r, role).issue(admin, role, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("issue on resource " + resourceName + " failed", e);
        } finally {
            Zeroize.wipe(pwd);
        }
        // 租约到期：重开 admin 连接撤销
        Lease lease = leases.register("resource/" + resourceName + "/" + roleName, ttl, l -> dropQuietly(minted.username(), role));
        return new IssuedCred(minted.username(), minted.password(), lease.leaseId(), lease.expireAt());
    }

    @Override
    public void revoke(String leaseId) { leases.revoke(leaseId); }

    private void dropQuietly(String username, RoleDef role) {
        ResourceRecord r = record();
        byte[] pwd = r.adminPassword().getBytes(StandardCharsets.UTF_8);
        try (Connection admin = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), new String(pwd, StandardCharsets.UTF_8))) {
            adapterFor(r, role).revoke(admin, username, role);
        } catch (Exception e) {
            throw new IllegalStateException("revoke on resource " + resourceName + " failed", e);
        } finally {
            Zeroize.wipe(pwd);
        }
    }
}
```

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl engine test -Dtest=DbDynamicEngineIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/DbDynamicEngine.java engine/src/test/java/io/custos/engine/resource/DbDynamicEngineIT.java
git commit -m "feat(engine): DbDynamicEngine — per-resource dynamic credential engine"
```

---

### Task 7: ResourceManager（注册/列表/轮换 + 挂 registry + 审计）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/resource/ResourceManager.java`
- Test: `engine/src/test/java/io/custos/engine/resource/ResourceManagerIT.java`

- [ ] **Step 1: 写失败 IT** —— 装配 MySQL + JimmerStorage + ResourceStore + DefaultLeaseManager + SecretsEngineRegistry + HashChainAuditLog。验：`register(record)`（试连校验通过）→ `require("appdb")` 返回 DbDynamicEngine → `issue` 可签发；`list()` 含 appdb；`rotateAdminKey("appdb", "newpwd")` 后 `get` 的 adminPassword 变更；`unregister("appdb")` 后 `require` 抛异常。注册时审计链新增一条（actor=admin, action=register）。坏 admin 凭证 register → 抛校验异常。

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=ResourceManagerIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**

```java
package io.custos.engine.resource;

import io.custos.engine.audit.AuditLog;
import io.custos.engine.audit.AuditRecord;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.secrets.SecretsEngineRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/** 资源生命周期：注册（试连校验→存→挂 registry→审计）、列表、轮换高权限钥、注销。 */
public final class ResourceManager {
    private final ResourceStore store;
    private final SecretsEngineRegistry registry;
    private final LeaseManager leases;
    private final AuditLog audit;

    public ResourceManager(ResourceStore store, SecretsEngineRegistry registry, LeaseManager leases, AuditLog audit) {
        this.store = store; this.registry = registry; this.leases = leases; this.audit = audit;
    }

    /** 解封后调用：把已持久化的资源全部挂回 registry。 */
    public void mountAll() {
        for (String name : store.listNames()) mount(name);
    }

    private void mount(String name) { registry.mount(name, new DbDynamicEngine(name, store, leases)); }

    public DbDynamicEngine require(String name) { return (DbDynamicEngine) registry.require(name); }

    public List<String> list() { return store.listNames(); }

    public void register(ResourceRecord r) {
        validate(r);
        store.put(r);
        mount(name(r));
        record("register", r.name());
    }

    public void rotateAdminKey(String name, String newPassword) {
        ResourceRecord r = store.get(name).orElseThrow(() -> new IllegalArgumentException("no resource: " + name));
        ResourceRecord updated = new ResourceRecord(r.name(), r.type(), r.dialect(), r.jdbcUrl(),
                r.adminUsername(), newPassword, r.roles());
        validate(updated);
        store.put(updated);
        record("rotate-admin", name);
    }

    public void unregister(String name) {
        store.delete(name);
        // 重挂：从 registry 移除（registry 无 unmount 时，覆盖为抛错引擎或重建 registry——见 Task 9 装配；此处仅删存储 + 审计）
        record("unregister", name);
    }

    /** 用提供的 admin 凭证开一次连接，验证连通 + 凭证有效。 */
    private void validate(ResourceRecord r) {
        try (Connection c = DriverManager.getConnection(r.jdbcUrl(), r.adminUsername(), r.adminPassword())) {
            if (!c.isValid(5)) throw new IllegalStateException("admin connection invalid");
        } catch (Exception e) {
            throw new IllegalArgumentException("resource validation failed for " + r.name() + ": " + e.getMessage(), e);
        }
    }

    private String name(ResourceRecord r) { return r.name(); }

    private void record(String action, String resourceName) {
        if (audit == null) return;
        audit.append(new AuditRecord(System.currentTimeMillis(), "admin", "resource", "resource:" + resourceName,
                action, "ok", "", resourceName));
    }
}
```
> 注：`SecretsEngineRegistry` 当前无 unmount。Task 9 装配里，`unregister` 后重新 `mountAll`（registry 重建）或给 registry 加 `unmount(name)`。本任务先实现存储删除 + 审计；registry 卸载在 Task 9 处理（给 `SecretsEngineRegistry` 加 `void unmount(String name)` 一行：`mounts.remove(name)`，并在此调用）。

- [ ] **Step 3b: 给 SecretsEngineRegistry 加 unmount** — Modify `engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java`：加 `public void unmount(String name) { mounts.remove(name); }`。在 `ResourceManager.unregister` 里调用 `registry.unmount(name)`。

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl engine test -Dtest=ResourceManagerIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add engine/src/main/java/io/custos/engine/resource/ResourceManager.java engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java engine/src/test/java/io/custos/engine/resource/ResourceManagerIT.java
git commit -m "feat(engine): ResourceManager — register/list/rotate/unregister + audit"
```

---

### Task 8: broker 按 resource/role 解析

**Files:**
- Modify: `broker/src/main/java/io/custos/broker/QueryIntent.java`
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`
- Modify: `broker/src/main/java/io/custos/broker/McpQueryToolServer.java`
- Modify: `broker/src/test/java/io/custos/broker/BrokerServiceIT.java`
- Modify: `broker/src/test/java/io/custos/broker/McpQueryToolServerTest.java`

- [ ] **Step 1: 改 QueryIntent**
```java
package io.custos.broker;
/** 查询意图：tool=MCP 工具名；resource=注册的资源名；role=签发角色（默认 read-only）；sql=只读 SQL。 */
public record QueryIntent(String tool, String resource, String role, String sql) {
    public QueryIntent(String tool, String resource, String sql) { this(tool, resource, "read-only", sql); }
}
```

- [ ] **Step 2: 改 BrokerService** —— 构造器从 `(tokens, pdp, SecretsEngine creds, executor, jdbcUrl, audit)` 改为 `(tokens, pdp, ResourceManager resources, executor, audit)`；queryDb 用 `resources.require(intent.resource())` 取引擎与 jdbcUrl：

```java
// 关键改动：字段 + 构造器
private final io.custos.engine.resource.ResourceManager resources;
public BrokerService(TokenService tokens, Pdp pdp, io.custos.engine.resource.ResourceManager resources,
                     SecretlessQueryExecutor executor, io.custos.engine.audit.AuditLog audit) {
    this.tokens = tokens; this.pdp = pdp; this.resources = resources; this.executor = executor; this.audit = audit;
}

// queryDb：
public QueryResult queryDb(QueryIntent intent, String userToken) {
    TokenClaims claims = tokens.verify(userToken);
    String sub = "agent:" + AgentId.parse(claims.subject()).agent();
    String resourceObj = "tool:" + intent.tool();
    Decision d = pdp.decide(DecisionRequest.of(sub, resourceObj, "read"));
    if (!d.allowed()) { record(sub, intent.tool(), resourceObj, "deny", "", d.reason()); return QueryResult.denied(d.reason()); }
    var engine = resources.require(intent.resource());
    IssuedCred cred = engine.issue(intent.role(), java.time.Duration.ofHours(1));
    try {
        var rows = executor.runReadonly(engine.jdbcUrl(), cred, intent.sql());
        record(sub, intent.tool(), resourceObj, "allow", rows.size() + " rows", cred.leaseId());
        return QueryResult.ok(rows);
    } finally {
        engine.revoke(cred.leaseId());
    }
}
```
> 删除旧的单 `creds`/`jdbcUrl` 字段与双构造器。保留 `record(...)` 私有方法不变。

- [ ] **Step 3: 改 McpQueryToolServer** —— 工具 inputSchema 把 `schema` 改为 `resource`，加可选 `role`；handle 里 `new QueryIntent((String)args.get("tool"), (String)args.get("resource"), (String)args.getOrDefault("role","read-only"), (String)args.get("sql"))`。required 改为 `["tool","resource","sql","userToken"]`。

- [ ] **Step 4: 改 BrokerServiceIT + McpQueryToolServerTest** —— BrokerServiceIT 的 `broker()` 改为：建 JimmerStorage+ResourceStore+ResourceManager，`register` 一条 mysql 资源（admin root/root，role read-only schema appdb），`new BrokerService(tokens, pdp, resourceManager, new SecretlessQueryExecutor(), audit)`；查询用 `new QueryIntent("db/query_orders","appdb","SELECT COUNT(*) AS n FROM appdb.orders")`。McpQueryToolServerTest 的 deny 用例构造 BrokerService 时同样传 resourceManager（deny 路径不触达资源，可传一个空 ResourceManager 或 mock）。

- [ ] **Step 5: 跑 broker 测试** — Run: `mvn -pl broker -am test -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS（含 BrokerAuditWiringTest 需同步改构造器——见下）

- [ ] **Step 5b:** `BrokerAuditWiringTest`（纯单元）构造 BrokerService 也要改：deny 路径传一个最小 ResourceManager（不触达）。把其 `UnreachableSecretsEngine` 改为不再需要——deny 不调 resources。构造 `new BrokerService(tokens, pdp, dummyResourceManager, new SecretlessQueryExecutor(), audit)`，dummyResourceManager 可用 `new ResourceManager(new ResourceStore(memStorage), new SecretsEngineRegistry(), fakeLeases, null)`。

- [ ] **Step 6: Commit**
```bash
git add broker/
git commit -m "feat(broker): resolve resource/role per query via ResourceManager"
```

---

### Task 9: app — REST + 装配（去硬编码）

**Files:**
- Create: `app/src/main/java/io/custos/app/resource/ResourceController.java`
- Modify: `app/src/main/java/io/custos/app/operator/OperatorService.java`（assemble 重写 + UnsealedContext 加 resourceManager）
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（AdminTokenFilter 加 /resources；OperatorService 不再传 storage 作 broker admin）
- Modify: `app/src/main/java/io/custos/app/query/QueryController.java`（body 取 resource/role）
- Modify: `app/src/main/resources/application.yml`（删 target-jdbc-url/db-readonly-schema）
- Test: `app/src/test/java/io/custos/app/ResourceControllerIT.java`
- Modify: `app/src/test/java/io/custos/app/HostEndToEndIT.java` + `OperatorServiceTest.java`（加注册步）

- [ ] **Step 1: 改 OperatorService.assemble** —— 当前装配单 `DynamicDbCredentials`。改为：建 storage、audit、`DefaultLeaseManager`、`SecretsEngineRegistry`、`ResourceStore(storage)`、`ResourceManager(store, registry, leases, audit)`，调 `resourceManager.mountAll()`（载入已持久化资源），`new BrokerService(tokens, pdp, resourceManager, new SecretlessQueryExecutor(), audit)`，`ctx.set(new UnsealedContext(storage, audit, broker, resourceManager))`。删除 `adminJdbcUrl/adminUser/adminPwd` 字段与构造器入参、删除 `DriverManager.getConnection(admin...)` 与 `DynamicDbCredentials`。`UnsealedContext` 加 `ResourceManager resourceManager()` 访问器。

- [ ] **Step 2: 改 HostConfig** —— `operatorService(...)` bean 不再传 `props.getEngine().getStorageUrl/Username/Password` 作 broker admin（OperatorService 构造器相应缩减）。`adminTokenFilter` 的 `addUrlPatterns` 增加 `"/resources/*"`。

- [ ] **Step 3: 写 ResourceController**
```java
package io.custos.app.resource;

import io.custos.app.operator.OperatorService;
import io.custos.engine.resource.ResourceRecord;
import io.custos.engine.resource.RoleDef;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** admin-gated 资源接入端点。响应绝不含 adminPassword。 */
@RestController
@RequestMapping("/resources")
public class ResourceController {
    private final OperatorService op;
    public ResourceController(OperatorService op) { this.op = op; }

    @PostMapping
    public Map<String, Object> register(@RequestBody ResourceRecord body) {
        op.unsealed().resourceManager().register(body);
        return Map.of("name", body.name(), "status", "registered");
    }

    @GetMapping
    public List<String> list() { return op.unsealed().resourceManager().list(); }

    @PostMapping("/{name}/rotate-admin")
    public Map<String, Object> rotate(@PathVariable String name, @RequestBody Map<String, String> body) {
        op.unsealed().resourceManager().rotateAdminKey(name, body.get("adminPassword"));
        return Map.of("name", name, "status", "rotated");
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> remove(@PathVariable String name) {
        op.unsealed().resourceManager().unregister(name);
        return Map.of("name", name, "status", "removed");
    }
}
```
> `ResourceRecord` 是 Jackson 友好的 record，可直接作 `@RequestBody`。`GET /resources` 只回名字列表（绝不回 adminPassword）；如需详情，加 `GET /resources/{name}` 返回脱敏 DTO（name/type/dialect/jdbcUrl/roles，去掉 adminPassword）——本任务先实现 list 名字。

- [ ] **Step 4: 改 QueryController** —— body 取 `resource`/`role`：`new QueryIntent(body.get("tool"), body.get("resource"), body.getOrDefault("role","read-only"), body.get("sql"))`。

- [ ] **Step 5: 改 application.yml** —— 删 `broker.target-jdbc-url` 与 `broker.db-readonly-schema` 两行（broker 块若空则整块删）。

- [ ] **Step 6: 写 ResourceControllerIT + 改 HostEndToEndIT/OperatorServiceTest** —— ResourceControllerIT（Spring Boot Test + Testcontainers MySQL）：init+unseal → `POST /resources` 注册 appdb（无 token 返回 401；带 demo token 成功）→ `GET /resources` 含 appdb 且响应体不含密码字符串 → `POST /query_db`（resource=appdb）拿到行 → secretless。HostEndToEndIT/OperatorServiceTest：解封后先 `resourceManager().register(appdb 记录)` 再查询。

- [ ] **Step 7: 跑 app 测试** — Run: `mvn -pl app -am test -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 8: Commit**
```bash
git add app/
git commit -m "feat(app): /resources REST + load-on-unseal wiring, drop hardcoded target-jdbc-url"
```

---

### Task 10: CLI resource 子命令

**Files:**
- Modify: `cli/src/main/java/io/custos/cli/CustosCli.java`（加 resource register/list/rm/rotate-admin 子命令，包 REST）
- Modify: `cli/src/test/java/io/custos/cli/CustosCliHttpTest.java`（加请求形状断言）

- [ ] **Step 1: 写失败测试** —— 参照现有 `CustosCliHttpTest` 的 fake host 模式：断言 `resource register --name appdb --type mysql --dialect mysql --jdbc-url ... --admin-user custos --admin-password custospwd --role read-only` 向 `POST /resources` 发出含上述字段的 JSON body（adminPassword 在 body、不打印到 stdout）。

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl cli -am test -Dtest=CustosCliHttpTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: FAIL

- [ ] **Step 3: 写实现** —— picocli 子命令 `resource`，含 `register`/`list`/`rm`/`rotate-admin`，构造 ResourceRecord JSON POST 到 `<host>/resources`，带 `Authorization: Bearer <token>`。参照 CustosCli 现有 `query`/`operator` 子命令的 HTTP 调用样板。

- [ ] **Step 4: 跑确认通过** — Run: `mvn -pl cli -am test -Dtest=CustosCliHttpTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add cli/
git commit -m "feat(cli): resource register/list/rm/rotate-admin subcommands"
```

---

### Task 11: demo / 迁移 / 文档

**Files:**
- Modify: `examples/demo.md`（AC0 注册 + AC9 密钥托管铁证；query 改 resource=appdb）
- Modify: `examples/mcp-custos.sh`（解封后注册 appdb 资源）
- Modify: `examples/mcp_smoke_client.py`（query 用 resource/role）
- Modify: `docs/audit/AUDIT-PREP.md`（G3 更新：高权限凭证已 Barrier 托管 ✓，权限仍过大）

- [ ] **Step 1: demo.md** —— 在 AC1 解封后插 AC0：`custos --token $TOKEN resource register --name appdb --type db.relational --dialect mysql --jdbc-url jdbc:mysql://mysql:3306/appdb --admin-user custos --admin-password custospwd --role read-only`。AC3/AC4 的 query_db 改 `resource=appdb`（删 `schema`）。新增 AC9：`docker exec mysql ... SELECT HEX(svalue) FROM custos.custos_storage WHERE skey='resource/appdb'` 为密文，`grep -i custospwd` 无命中；`resource list` 不含密码；`rotate-admin` 演示。

- [ ] **Step 2: mcp-custos.sh** —— 解封三分片后、签 token 前，加一段 `curl -XPOST .../resources`（带 admin Bearer）注册 appdb 资源。

- [ ] **Step 3: mcp_smoke_client.py** —— `tools/call` 的 arguments 把 `schema` 改 `resource`，加 `role`。

- [ ] **Step 4: AUDIT-PREP.md** —— G3 行更新：从「JWT 签名钥/SVID CA 未托管」补一条资源 admin 凭证现状——「已 Barrier 加密托管 ✓；demo 仍用 GRANT ALL 的 custos 账号，权限过大，生产应换最小权限 admin 角色」。

- [ ] **Step 5: Commit**
```bash
git add examples/ docs/audit/AUDIT-PREP.md
git commit -m "docs(examples): AC0 resource register + AC9 key-custody proof; migrate to resource/role"
```

---

### Task 12: 全量门禁 + 看板卡

**Files:**
- Create: `docs/spec/module/M15-resource-onboarding.md`（docs-cockpit 卡）
- 产物：`docs/cockpit.html` 重渲

- [ ] **Step 1: 全量 verify（需 Docker）** — Run: `mvn -B clean verify` Expected: BUILD SUCCESS（engine/broker/app/cli 全绿，含所有新 IT + 迁移后回归）

- [ ] **Step 2: 写 M15 看板卡** —— 参照 `docs/spec/module/M14-hardening.md` 的 frontmatter（id/title/status/sprint/progress/desc/docs/subtasks），id=M15、sprint=v0.5、status=done(完成时)、docs 链 spec+plan、subtasks 列本计划 12 任务、每条按 schema 加 `@docs:`/`@code:` anchor（参照已锚定的 M01-M14 风格）。

- [ ] **Step 3: 重渲看板** — Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml` 然后 `python3 -m docs_cockpit lint --config docs-cockpit.yaml`（期望 0 error/0 warning）。

- [ ] **Step 4: Commit + 合并**
```bash
git add docs/spec/module/M15-resource-onboarding.md docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "docs(cockpit): M15 resource onboarding card"
# 全绿后 FF 合并 main：
git checkout main && git merge --ff-only impl/m15-resource-onboarding && git push origin main
```

---

## Self-Review

**1. Spec 覆盖：** §1.5 技术栈→全程零新增依赖遵守；§2.0 泛型模型→ResourceRecord.type + RoleKind（v0.5 仅 db.relational，Task 1）；§2.1 组件→Task 1-7 逐一；§3 密钥托管时序→DbDynamicEngine 的 Zeroize/按需连接（Task 6）+ ResourceStore 加密（Task 2）+ rotate（Task 7）；§4 REST/CLI→Task 9/10；§5 broker→Task 8；§6 迁移+AC0/AC9→Task 11；§7 测试→各 IT；§8 验收→Task 12；§9 YAGNI（不写 StaticSecretEngine/LLM/连接池）→遵守。无遗漏。

**2. 占位符扫描：** 适配器 DDL 给全；移植类（PG/IT 样板）明确指向要照搬的现有文件并给出目标结构，非「TODO」。ResourceStoreIT/各 IT 的容器装配指明照搬具体现有 IT 文件（JimmerStorageIT/DynamicDbCredentialsIT/PostgresDynamicCredentialsIT）——这是真实上下文复用，非占位。

**3. 类型一致：** `CredentialAdapter.issue→MintedCred(username,password)`；`DbDynamicEngine.issue→IssuedCred(username,password,leaseId,expireAt)`（engine 加 lease）；`BrokerService(tokens,pdp,ResourceManager,executor,audit)` 与 Task 9 装配一致；`QueryIntent(tool,resource,role,sql)` 与 broker/McpQueryToolServer/QueryController 一致；`ResourceManager.require→DbDynamicEngine`（有 `jdbcUrl()`）与 BrokerService 用法一致；`SecretsEngineRegistry` 加 `unmount`（Task 7 Step 3b）与 unregister 一致。
