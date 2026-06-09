# Custos MVP v0.1 — 引擎持久化（Jimmer）Implementation Plan（计划 2/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在引擎基座（计划 1/5）之上，以 TDD + **Jimmer ORM** 实现持久化层——MySQL 全密文存储、Jimmer 版 SealStore、哈希链防篡改审计、租约管理、动态 MySQL 只读凭证。

**Architecture:** Custos **自身元数据表**用 **Jimmer 不可变实体 + JSqlClient**（类型安全 DSL、无 N+1）持久化（决策见 ADR-8 / `docs/research/jimmer.md`）。`byte[]` 列存 **Barrier 密文**，加解密在 service 层完成，Jimmer 不接触明文。**裸 JDBC 仅保留两处非 ORM 场景**：动态凭证的 `CREATE/DROP USER`（目标库账号管理）、经纪层的 secretless 任意 SELECT（计划 5）。集成测试用 Testcontainers 起真实 MySQL。

**Tech Stack:** Java 21 · **Jimmer 0.10.10**（jimmer-sql + jimmer-apt 编译时）· MySQL 8 · Testcontainers · JUnit 5 ·（依赖计划 1 的 `IntlSuite`/`DefaultBarrier`/`SealManager`/`SealStore`）

> 前置：计划 1/5 完成。对应 spec §3.4–3.6、§4、ADR-8，详设 `docs/design/02` §7/§11、`docs/design/06` §3、`docs/design/08` ADR-8。
> **Jimmer 是编译时框架**：实体为 `@Entity interface`，`mvn` 编译时由 jimmer-apt 生成 `XxxDraft`/`XxxTable`/`XxxProps`。测试引用这些生成类，首次编译即生成。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/pom.xml` | 加 jimmer-sql + jimmer-apt（annotationProcessorPaths）+ mysql + testcontainers |
| `engine/src/main/resources/db/schema.sql` | 建表 DDL（列名避开 KEY/VALUE 保留字）|
| `engine/src/main/java/io/custos/engine/persistence/JimmerClients.java` | 由 DataSource 构建 JSqlClient |
| `engine/src/main/java/io/custos/engine/storage/StorageEntry.java` | `@Entity` 存储行 |
| `engine/src/main/java/io/custos/engine/storage/Storage.java` | 存储抽象 |
| `engine/src/main/java/io/custos/engine/storage/JimmerStorage.java` | Jimmer 全密文存储 |
| `engine/src/main/java/io/custos/engine/seal/SealConfigEntry.java` | `@Entity` 解封配置行 |
| `engine/src/main/java/io/custos/engine/seal/JimmerSealStore.java` | SealStore 的 Jimmer 实现 |
| `engine/src/main/java/io/custos/engine/audit/AuditRow.java` | `@Entity` 审计行 |
| `engine/src/main/java/io/custos/engine/audit/{AuditRecord,VerifyResult,AuditLog,HashChainAuditLog}.java` | 哈希链审计 |
| `engine/src/main/java/io/custos/engine/lease/LeaseRow.java` | `@Entity` 租约行 |
| `engine/src/main/java/io/custos/engine/lease/{Lease,Revoker,LeaseManager,DefaultLeaseManager}.java` | 租约 |
| `engine/src/main/java/io/custos/engine/secrets/{IssuedCred,DynamicDbCredentials}.java` | 动态 DB 凭证（裸 JDBC 账号管理 + Jimmer 租约）|
| `engine/src/test/java/io/custos/engine/**` | Testcontainers 集成测试 |

---

## Task 1: Jimmer 接入 + StorageEntry 实体 + JimmerStorage（全密文）

**Files:**
- Modify: `engine/pom.xml`
- Create: `engine/src/main/resources/db/schema.sql`
- Create: `engine/src/main/java/io/custos/engine/persistence/JimmerClients.java`
- Create: `engine/src/main/java/io/custos/engine/storage/StorageEntry.java`
- Create: `engine/src/main/java/io/custos/engine/storage/Storage.java`
- Create: `engine/src/main/java/io/custos/engine/storage/JimmerStorage.java`
- Test: `engine/src/test/java/io/custos/engine/storage/JimmerStorageIT.java`

- [ ] **Step 1: 加 Jimmer/MySQL/Testcontainers 依赖 + APT 到 `engine/pom.xml`**

在 `<dependencies>` 加：
```xml
    <dependency>
      <groupId>org.babyfish.jimmer</groupId>
      <artifactId>jimmer-sql</artifactId>
      <version>0.10.10</version>
    </dependency>
    <!-- 实测必需：jimmer-apt 生成的 XxxDraft 引用 Jackson 注解，而 jimmer-sql 把 jackson 设为 optional，
         不显式引入则 APT 生成代码编译期报 “package com.fasterxml.jackson.annotation does not exist”。 -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.2</version>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <version>8.4.0</version>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <version>1.19.8</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>1.19.8</version>
      <scope>test</scope>
    </dependency>
    <!-- 测试日志绑定：强制 slf4j-api 2.x 与 slf4j-simple 2.x 对齐。jimmer 传递的 slf4j-api 1.7.36
         会让 2.x 的 simple 绑定失败而退化为 NOP，导致 Testcontainers 的诊断日志（含连接失败原因）全部消失。 -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.16</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.16</version>
      <scope>test</scope>
    </dependency>
```
并在 `engine/pom.xml` 加 `<build>`（jimmer-apt 注解处理器 + failsafe 跑 IT + 钉死 Docker API 版本）：
```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.babyfish.jimmer</groupId>
              <artifactId>jimmer-apt</artifactId>
              <version>0.10.10</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <!--
        api.version=1.40（关键，实测坑）：Testcontainers 1.19.8 自带 docker-java 3.3.6，默认协商的
        Docker API 版本低于 1.40；当本机/CI 的 Docker Engine 较新（如 29.x，MinAPIVersion=1.40）时，
        引擎对低版本请求直接回 HTTP 400，Testcontainers 报 “Could not find a valid Docker environment”。
        把系统属性 api.version 钉到 1.40（所有受支持引擎都接受的下限）即可，对新旧引擎与 CI 均安全。
        docker-java 读系统属性 `api.version` / 环境变量 `DOCKER_API_VERSION`；.testcontainers.properties
        里的 api.version 键不会透传给 docker-java，必须走 systemPropertyVariables。
      -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <systemPropertyVariables><api.version>1.40</api.version></systemPropertyVariables>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <systemPropertyVariables><api.version>1.40</api.version></systemPropertyVariables>
        </configuration>
        <executions>
          <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```
> failsafe 版本由父 `pom.xml` 的 `pluginManagement` 统一管理（3.2.5，与 surefire 对齐）。
> **本地前置（仅本机一次性）**：`~/.testcontainers.properties` 里设 `docker.host`（本机用 `tcp://127.0.0.1:2375`，需在 Docker Desktop 勾选 “Expose daemon on tcp without TLS”；Linux/CI 自动探测 unix socket 无需配）。docker.host 属机器级配置，不入库。

- [ ] **Step 2: 建表 DDL（列名避开 SQL 保留字 KEY/VALUE）**

`engine/src/main/resources/db/schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS custos_storage (
  skey       VARCHAR(255) PRIMARY KEY,
  svalue     LONGBLOB NOT NULL,          -- Barrier 密文
  updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_seal_config (
  ckey VARCHAR(64) PRIMARY KEY,
  cval LONGBLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_audit (
  seq            BIGINT AUTO_INCREMENT PRIMARY KEY,
  ts             BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
  task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64),
  decision VARCHAR(32), result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
  prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL
);
CREATE TABLE IF NOT EXISTS custos_lease (
  lease_id      VARCHAR(160) PRIMARY KEY,
  resource_path VARCHAR(512) NOT NULL,
  issued_at     BIGINT NOT NULL, expire_at BIGINT NOT NULL,
  revoked       TINYINT NOT NULL DEFAULT 0
);
```

- [ ] **Step 3: 写失败测试（Testcontainers：put→get 密文往返；落盘是密文；list 前缀）**

`engine/src/test/java/io/custos/engine/storage/JimmerStorageIT.java`:
```java
package io.custos.engine.storage;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JimmerStorageIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private MysqlDataSource ds;
    private JimmerStorage storage;

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_storage (skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");
        }
        JSqlClient sql = JimmerClients.of(ds);
        storage = new JimmerStorage(sql, barrier());
    }

    @Test
    void putThenGetRoundTrips() {
        storage.put("k1", "secret-value".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), storage.get("k1").orElseThrow());
    }

    @Test
    void storedBytesAreCiphertext() throws Exception {
        storage.put("k2", "plaintext-marker".getBytes(StandardCharsets.UTF_8));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT svalue FROM custos_storage WHERE skey='k2'")) {
            assertTrue(rs.next());
            assertFalse(new String(rs.getBytes(1), StandardCharsets.UTF_8).contains("plaintext-marker"));
        }
    }

    @Test
    void listByPrefix() {
        storage.put("p/a", "1".getBytes());
        storage.put("p/b", "2".getBytes());
        storage.put("q/c", "3".getBytes());
        assertEquals(2, storage.list("p/").size());
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=JimmerStorageIT`
Expected: 编译失败（JimmerClients/StorageEntry/Storage/JimmerStorage 未定义）。

- [ ] **Step 5: 写 JimmerClients 工厂**

`engine/src/main/java/io/custos/engine/persistence/JimmerClients.java`:
```java
package io.custos.engine.persistence;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.dialect.MySqlDialect;
import org.babyfish.jimmer.sql.runtime.ConnectionManager;

import javax.sql.DataSource;

/** 由 DataSource 构建 JSqlClient（MySQL 方言）。app 模块用 Spring starter 装配，引擎/测试用此工厂。 */
public final class JimmerClients {
    private JimmerClients() {}

    public static JSqlClient of(DataSource dataSource) {
        return JSqlClient.newBuilder()
                .setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
                .setDialect(new MySqlDialect())
                .build();
    }
}
```

- [ ] **Step 6: 写 StorageEntry 实体**

`engine/src/main/java/io/custos/engine/storage/StorageEntry.java`:
```java
package io.custos.engine.storage;

import org.babyfish.jimmer.sql.Column;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Table;

/** 通用密文 KV 行；value 为 Barrier 密文（明文加解密在 service 层）。列名避开 KEY/VALUE 保留字。 */
@Entity
@Table(name = "custos_storage")
public interface StorageEntry {

    @Id
    @Column(name = "skey")
    String key();

    @Column(name = "svalue")
    byte[] value();

    @Column(name = "updated_at")
    long updatedAt();
}
```

- [ ] **Step 7: 写 Storage 接口 + JimmerStorage**

`engine/src/main/java/io/custos/engine/storage/Storage.java`:
```java
package io.custos.engine.storage;

import java.util.List;
import java.util.Optional;

public interface Storage {
    Optional<byte[]> get(String key);
    void put(String key, byte[] value);
    void delete(String key);
    List<String> list(String prefix);
}
```

`engine/src/main/java/io/custos/engine/storage/JimmerStorage.java`:
```java
package io.custos.engine.storage;

import io.custos.engine.barrier.Barrier;
import org.babyfish.jimmer.sql.JSqlClient;

import java.util.List;
import java.util.Optional;

/** Jimmer 全密文存储：put 时 barrier.seal，get 时 barrier.open；save 按 @Id upsert。 */
public final class JimmerStorage implements Storage {

    private final JSqlClient sql;
    private final Barrier barrier;

    public JimmerStorage(JSqlClient sql, Barrier barrier) {
        this.sql = sql;
        this.barrier = barrier;
    }

    @Override
    public Optional<byte[]> get(String key) {
        StorageEntry e = sql.getEntities().findById(StorageEntry.class, key);
        return e == null ? Optional.empty() : Optional.of(barrier.open(e.value()));
    }

    @Override
    public void put(String key, byte[] value) {
        byte[] sealed = barrier.seal(value);
        sql.getEntities().save(
                StorageEntryDraft.$.produce(d -> {
                    d.setKey(key);
                    d.setValue(sealed);
                    d.setUpdatedAt(System.currentTimeMillis());
                })
        );
    }

    @Override
    public void delete(String key) {
        sql.getEntities().delete(StorageEntry.class, key);   // Entities.delete(Class,id)
    }

    @Override
    public List<String> list(String prefix) {
        StorageEntryTable t = StorageEntryTable.$;
        return sql.createQuery(t)
                .where(t.key().like(prefix + "%"))
                .select(t.key())
                .execute();
    }
}
```
> `StorageEntryDraft` / `StorageEntryTable` 由 jimmer-apt 在编译时生成（首次 `mvn` 编译产生）。

- [ ] **Step 8: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=JimmerStorageIT`
Expected: PASS（3 个用例；编译时生成 Jimmer 代码；首次拉取 mysql:8.0 镜像）。

- [ ] **Step 9: 提交**
```bash
git add engine/pom.xml engine/src/main/resources/db/schema.sql engine/src/main/java/io/custos/engine/persistence engine/src/main/java/io/custos/engine/storage engine/src/test/java/io/custos/engine/storage
git commit -m "feat(engine): Jimmer ciphertext storage (entity + JSqlClient) with barrier"
```

---

## Task 2: JimmerSealStore（SealStore 接到 Jimmer）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/seal/SealConfigEntry.java`
- Create: `engine/src/main/java/io/custos/engine/seal/JimmerSealStore.java`
- Test: `engine/src/test/java/io/custos/engine/seal/JimmerSealStoreIT.java`

- [ ] **Step 1: 写失败测试（跨实例从 MySQL 恢复解封）**

`engine/src/test/java/io/custos/engine/seal/JimmerSealStoreIT.java`:
```java
package io.custos.engine.seal;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JimmerSealStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private JSqlClient sql;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
        }
        sql = JimmerClients.of(ds);
    }

    @Test
    void unsealRecoversFromJimmerStoreAcrossInstances() {
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), new JimmerSealStore(sql)).init(5, 3);

        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new JimmerSealStore(sql));
        assertTrue(mgr.status().sealed());
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(2));
        assertFalse(mgr.submitUnsealKey(shares.get(4)).sealed());

        DefaultBarrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        assertArrayEquals("ok".getBytes(), barrier.open(barrier.seal("ok".getBytes())));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=JimmerSealStoreIT`
Expected: 编译失败（SealConfigEntry/JimmerSealStore 未定义）。

- [ ] **Step 3: 写 SealConfigEntry 实体**

`engine/src/main/java/io/custos/engine/seal/SealConfigEntry.java`:
```java
package io.custos.engine.seal;

import org.babyfish.jimmer.sql.Column;
import org.babyfish.jimmer.sql.Entity;
import org.babyfish.jimmer.sql.Id;
import org.babyfish.jimmer.sql.Table;

@Entity
@Table(name = "custos_seal_config")
public interface SealConfigEntry {

    @Id
    @Column(name = "ckey")
    String key();

    @Column(name = "cval")
    byte[] value();
}
```

- [ ] **Step 4: 实现 JimmerSealStore**

`engine/src/main/java/io/custos/engine/seal/JimmerSealStore.java`:
```java
package io.custos.engine.seal;

import org.babyfish.jimmer.sql.JSqlClient;

import java.util.Optional;

/** SealStore 的 Jimmer 实现（custos_seal_config）。wrapped_barrier 为密文；threshold/shares 为元数据（4字节）。 */
public final class JimmerSealStore implements SealStore {

    private final JSqlClient sql;

    public JimmerSealStore(JSqlClient sql) {
        this.sql = sql;
    }

    @Override
    public Optional<byte[]> get(String key) {
        SealConfigEntry e = sql.getEntities().findById(SealConfigEntry.class, key);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    @Override
    public void put(String key, byte[] value) {
        sql.getEntities().save(
                SealConfigEntryDraft.$.produce(d -> {
                    d.setKey(key);
                    d.setValue(value);
                })
        );
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=JimmerSealStoreIT`
Expected: PASS。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/seal/SealConfigEntry.java engine/src/main/java/io/custos/engine/seal/JimmerSealStore.java engine/src/test/java/io/custos/engine/seal/JimmerSealStoreIT.java
git commit -m "feat(engine): Jimmer-backed seal store"
```

---

## Task 3: 哈希链审计（Jimmer 实体）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/audit/AuditRow.java`
- Create: `engine/src/main/java/io/custos/engine/audit/{AuditRecord,VerifyResult,AuditLog}.java`
- Create: `engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java`
- Test: `engine/src/test/java/io/custos/engine/audit/HashChainAuditLogIT.java`

- [ ] **Step 1: 写失败测试（append→verify OK；改一条→定位断链；敏感脱敏）**

`engine/src/test/java/io/custos/engine/audit/HashChainAuditLogIT.java`:
```java
package io.custos.engine.audit;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class HashChainAuditLogIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private MysqlDataSource ds;
    private HashChainAuditLog audit;

    @BeforeEach
    void setUp() throws Exception {
        ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_audit (
                seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
                task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32),
                result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
                prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)""");
        }
        JSqlClient sql = JimmerClients.of(ds);
        audit = new HashChainAuditLog(sql, new IntlSuite(), new byte[32]);
    }

    private AuditRecord rec(String actor, String action) {
        return new AuditRecord(System.currentTimeMillis(), actor, "task", "db:orders", action, "ALLOW", "digest", "pwd=xxx");
    }

    @Test
    void appendThenVerifyPasses() {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentB", "read"));
        assertTrue(audit.verify().ok());
    }

    @Test
    void tamperingBreaksChain() throws Exception {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentA", "read"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE custos_audit SET action='write' WHERE seq=1");
        }
        var r = audit.verify();
        assertFalse(r.ok());
        assertEquals(1L, r.brokenAtSeq());
    }

    @Test
    void sensitiveFieldHmacNotPlaintext() throws Exception {
        audit.append(rec("agentA", "read"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT sensitive_hmac FROM custos_audit WHERE seq=1")) {
            assertTrue(rs.next());
            assertNotEquals("pwd=xxx", rs.getString(1));
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=HashChainAuditLogIT`
Expected: 编译失败（audit 类未定义）。

- [ ] **Step 3: 写 AuditRow 实体 + AuditRecord/VerifyResult/AuditLog**

`engine/src/main/java/io/custos/engine/audit/AuditRow.java`:
```java
package io.custos.engine.audit;

import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

/** 审计行（只追加）。seq 自增主键。 */
@Entity
@Table(name = "custos_audit")
public interface AuditRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long seq();

    long ts();
    String actor();
    @Nullable String task();
    @Nullable String resource();
    @Nullable String action();
    @Nullable String decision();

    @Column(name = "result_digest")
    @Nullable String resultDigest();

    @Column(name = "sensitive_hmac")
    @Nullable String sensitiveHmac();

    @Column(name = "prev_hash")
    String prevHash();

    @Column(name = "chain_hash")
    String chainHash();
}
```

`engine/src/main/java/io/custos/engine/audit/AuditRecord.java`:
```java
package io.custos.engine.audit;

public record AuditRecord(long ts, String actor, String task, String resource,
                          String action, String decision, String resultDigest, String sensitiveRaw) {}
```

`engine/src/main/java/io/custos/engine/audit/VerifyResult.java`:
```java
package io.custos.engine.audit;

public record VerifyResult(boolean ok, long brokenAtSeq) {
    public static VerifyResult passed() { return new VerifyResult(true, -1); }
    public static VerifyResult brokenAt(long seq) { return new VerifyResult(false, seq); }
}
```

`engine/src/main/java/io/custos/engine/audit/AuditLog.java`:
```java
package io.custos.engine.audit;

public interface AuditLog {
    void append(AuditRecord record);
    VerifyResult verify();
}
```

- [ ] **Step 4: 实现 HashChainAuditLog（Jimmer DSL）**

`engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java`:
```java
package io.custos.engine.audit;

import io.custos.engine.crypto.CipherSuite;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** 哈希链审计：chain_hash_n = H(prev_hash || canonical)；敏感字段 HMAC 脱敏；只追加。Jimmer 实体读写。 */
public final class HashChainAuditLog implements AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final JSqlClient sql;
    private final CipherSuite suite;
    private final byte[] auditKey;

    public HashChainAuditLog(JSqlClient sql, CipherSuite suite, byte[] auditKey) {
        this.sql = sql;
        this.suite = suite;
        this.auditKey = auditKey.clone();
    }

    @Override
    public void append(AuditRecord r) {
        String prev = lastChainHash();
        String sensitiveHmac = hex(suite.hmac(auditKey, nz(r.sensitiveRaw()).getBytes(StandardCharsets.UTF_8)));
        String canonical = canonical(r.ts(), r.actor(), r.task(), r.resource(), r.action(), r.decision(), r.resultDigest(), sensitiveHmac);
        String chain = hex(suite.hash((prev + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
        // 审计行无 @Id(自增)/@Key → 必须 INSERT_ONLY（默认 UPSERT 会报 "neither id nor key"）
        sql.getEntities().saveCommand(
                AuditRowDraft.$.produce(d -> {
                    d.setTs(r.ts());
                    d.setActor(r.actor());
                    d.setTask(r.task());
                    d.setResource(r.resource());
                    d.setAction(r.action());
                    d.setDecision(r.decision());
                    d.setResultDigest(r.resultDigest());
                    d.setSensitiveHmac(sensitiveHmac);
                    d.setPrevHash(prev);
                    d.setChainHash(chain);
                    // seq 不设置 → DB 自增
                })
        ).setMode(SaveMode.INSERT_ONLY).execute();
    }

    @Override
    public VerifyResult verify() {
        AuditRowTable t = AuditRowTable.$;
        List<AuditRow> rows = sql.createQuery(t).orderBy(t.seq().asc()).select(t).execute();
        String expectedPrev = GENESIS;
        for (AuditRow row : rows) {
            if (!row.prevHash().equals(expectedPrev)) return VerifyResult.brokenAt(row.seq());
            String canonical = canonical(row.ts(), row.actor(), row.task(), row.resource(),
                    row.action(), row.decision(), row.resultDigest(), row.sensitiveHmac());
            String recompute = hex(suite.hash((row.prevHash() + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
            if (!recompute.equals(row.chainHash())) return VerifyResult.brokenAt(row.seq());
            expectedPrev = row.chainHash();
        }
        return VerifyResult.passed();
    }

    private String lastChainHash() {
        AuditRowTable t = AuditRowTable.$;
        List<String> last = sql.createQuery(t).orderBy(t.seq().desc()).select(t.chainHash()).limit(1).execute();
        return last.isEmpty() ? GENESIS : last.get(0);
    }

    private static String canonical(long ts, String actor, String task, String resource,
                                    String action, String decision, String resultDigest, String sensitiveHmac) {
        return String.join("|", String.valueOf(ts), nz(actor), nz(task), nz(resource),
                nz(action), nz(decision), nz(resultDigest), nz(sensitiveHmac));
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=HashChainAuditLogIT`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/audit engine/src/test/java/io/custos/engine/audit
git commit -m "feat(engine): tamper-evident hash-chain audit via Jimmer entity"
```

---

## Task 4: 租约管理（Jimmer 实体）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/lease/LeaseRow.java`
- Create: `engine/src/main/java/io/custos/engine/lease/{Lease,Revoker,LeaseManager,DefaultLeaseManager}.java`
- Test: `engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java`:
```java
package io.custos.engine.lease;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.persistence.JimmerClients;
import org.babyfish.jimmer.sql.JSqlClient;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DefaultLeaseManagerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private DefaultLeaseManager leases;

    @BeforeEach
    void setUp() throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_lease (
                lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL,
                issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)""");
        }
        JSqlClient sql = JimmerClients.of(ds);
        leases = new DefaultLeaseManager(sql);
    }

    @Test
    void revokeCallsRevoker() {
        AtomicInteger revoked = new AtomicInteger();
        Lease lease = leases.register("db/creds/orders-ro/a", Duration.ofHours(1), l -> revoked.incrementAndGet());
        leases.revoke(lease.leaseId());
        assertEquals(1, revoked.get());
    }

    @Test
    void revokePrefixRevokesSubtree() {
        AtomicInteger n = new AtomicInteger();
        Revoker r = l -> n.incrementAndGet();
        leases.register("db/creds/orders-ro/a", Duration.ofHours(1), r);
        leases.register("db/creds/orders-ro/b", Duration.ofHours(1), r);
        leases.register("db/creds/other/c", Duration.ofHours(1), r);
        assertEquals(2, leases.revokePrefix("db/creds/orders-ro/"));
        assertEquals(2, n.get());
    }

    @Test
    void renewExtendsExpiry() {
        Lease lease = leases.register("db/creds/x", Duration.ofMinutes(10), l -> {});
        assertTrue(leases.renew(lease.leaseId(), Duration.ofHours(2)).expireAt() > lease.expireAt());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DefaultLeaseManagerIT`
Expected: 编译失败（lease 类未定义）。

- [ ] **Step 3: 写 LeaseRow 实体 + Lease/Revoker/LeaseManager**

`engine/src/main/java/io/custos/engine/lease/LeaseRow.java`:
```java
package io.custos.engine.lease;

import org.babyfish.jimmer.sql.*;

@Entity
@Table(name = "custos_lease")
public interface LeaseRow {

    @Id
    @Column(name = "lease_id")
    String leaseId();

    @Column(name = "resource_path")
    String resourcePath();

    @Column(name = "issued_at")
    long issuedAt();

    @Column(name = "expire_at")
    long expireAt();

    boolean revoked();
}
```

`engine/src/main/java/io/custos/engine/lease/Lease.java`:
```java
package io.custos.engine.lease;

public record Lease(String leaseId, String resourcePath, long issuedAt, long expireAt) {}
```

`engine/src/main/java/io/custos/engine/lease/Revoker.java`:
```java
package io.custos.engine.lease;

@FunctionalInterface
public interface Revoker {
    void revoke(Lease lease);
}
```

`engine/src/main/java/io/custos/engine/lease/LeaseManager.java`:
```java
package io.custos.engine.lease;

import java.time.Duration;

public interface LeaseManager {
    Lease register(String resourcePath, Duration ttl, Revoker revoker);
    Lease renew(String leaseId, Duration increment);
    void revoke(String leaseId);
    int revokePrefix(String prefix);
}
```

- [ ] **Step 4: 实现 DefaultLeaseManager（Jimmer DSL + 后台扫描）**

`engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java`:
```java
package io.custos.engine.lease;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 租约：register/renew/revoke/revokePrefix；后台扫描到期自动撤销。Revoker 留内存映射（MVP 单节点）。 */
public final class DefaultLeaseManager implements LeaseManager {

    private final JSqlClient sql;
    private final ConcurrentHashMap<String, Revoker> revokers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor();

    public DefaultLeaseManager(JSqlClient sql) {
        this.sql = sql;
        scanner.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public Lease register(String resourcePath, Duration ttl, Revoker revoker) {
        String id = resourcePath + "/" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expire = now + ttl.toMillis();
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(id);
            d.setResourcePath(resourcePath);
            d.setIssuedAt(now);
            d.setExpireAt(expire);
            d.setRevoked(false);
        })).setMode(SaveMode.INSERT_ONLY).execute();   // 新行
        revokers.put(id, revoker);
        return new Lease(id, resourcePath, now, expire);
    }

    @Override
    public Lease renew(String leaseId, Duration increment) {
        long expire = System.currentTimeMillis() + increment.toMillis();
        // 残缺对象 + UPDATE_ONLY：只更新 expire_at（避免 UPSERT 的部分插入触发 NOT NULL）
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(leaseId);
            d.setExpireAt(expire);
        })).setMode(SaveMode.UPDATE_ONLY).execute();
        LeaseRow row = sql.getEntities().findById(LeaseRow.class, leaseId);
        return new Lease(leaseId, row.resourcePath(), row.issuedAt(), row.expireAt());
    }

    @Override
    public void revoke(String leaseId) {
        LeaseRow row = sql.getEntities().findById(LeaseRow.class, leaseId);
        if (row == null) return;
        Revoker r = revokers.remove(leaseId);
        if (r != null) r.revoke(new Lease(leaseId, row.resourcePath(), row.issuedAt(), row.expireAt()));
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(leaseId);
            d.setRevoked(true);
        })).setMode(SaveMode.UPDATE_ONLY).execute();
    }

    @Override
    public int revokePrefix(String prefix) {
        LeaseRowTable t = LeaseRowTable.$;
        List<String> ids = sql.createQuery(t)
                .where(t.resourcePath().like(prefix + "%"))
                .where(t.revoked().eq(false))
                .select(t.leaseId())
                .execute();
        ids.forEach(this::revoke);
        return ids.size();
    }

    private void sweepExpired() {
        try {
            LeaseRowTable t = LeaseRowTable.$;
            long now = System.currentTimeMillis();
            List<String> expired = sql.createQuery(t)
                    .where(t.expireAt().lt(now))
                    .where(t.revoked().eq(false))
                    .select(t.leaseId())
                    .execute();
            for (String id : expired) {
                try { revoke(id); } catch (RuntimeException ignore) { /* 重试 + 告警（计划 5 接监控）*/ }
            }
        } catch (RuntimeException ignore) {
            // 下个周期重试
        }
    }
}
```
> 说明：Jimmer `save` 对带 `@Id` 且只设置部分标量的对象执行**仅更新所设列**（残缺对象保存），因此 `renew`/`revoke` 只改 expire_at / revoked。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DefaultLeaseManagerIT`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/lease engine/src/test/java/io/custos/engine/lease
git commit -m "feat(engine): lease manager via Jimmer entity (TTL, revoke, prefix, auto-expiry)"
```

---

## Task 5: 动态 MySQL 只读凭证（裸 JDBC 账号管理 + Jimmer 租约）

> **边界**：`CREATE/DROP USER`、`GRANT` 是目标库的 DDL/账号管理，**非 ORM 操作 → 用裸 JDBC admin 连接**；租约持久化复用 Task 4 的 Jimmer `LeaseManager`。

**Files:**
- Create: `engine/src/main/java/io/custos/engine/secrets/IssuedCred.java`
- Create: `engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java`
- Test: `engine/src/test/java/io/custos/engine/secrets/DynamicDbCredentialsIT.java`

- [ ] **Step 1: 写失败测试（签发临时只读账号→可查不可写→撤销后失效）**

`engine/src/test/java/io/custos/engine/secrets/DynamicDbCredentialsIT.java`:
```java
package io.custos.engine.secrets;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.persistence.JimmerClients;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DynamicDbCredentialsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root");

    private Connection admin;
    private DynamicDbCredentials creds;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", "root");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(160) PRIMARY KEY, resource_path VARCHAR(512), issued_at BIGINT, expire_at BIGINT, revoked TINYINT DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1)");
        }
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl()); ds.setUser("root"); ds.setPassword("root");
        creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(JimmerClients.of(ds)), MYSQL.getJdbcUrl());
    }

    @Test
    void issuedCredReadsButCannotWrite() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        try (Connection user = DriverManager.getConnection(MYSQL.getJdbcUrl(), c.username(), c.password());
             Statement st = user.createStatement()) {
            assertTrue(st.executeQuery("SELECT COUNT(*) FROM appdb.orders").next());
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO appdb.orders VALUES (2)"));
        }
    }

    @Test
    void revokingDropsUser() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        creds.revoke(c.leaseId());
        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(MYSQL.getJdbcUrl(), c.username(), c.password()));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DynamicDbCredentialsIT`
Expected: 编译失败（IssuedCred/DynamicDbCredentials 未定义）。

- [ ] **Step 3: 写 IssuedCred + DynamicDbCredentials（裸 JDBC 账号管理）**

`engine/src/main/java/io/custos/engine/secrets/IssuedCred.java`:
```java
package io.custos.engine.secrets;

/** 现场签发的临时凭证（仅经纪层持有，绝不返回 LLM）。 */
public record IssuedCred(String username, String password, String leaseId, long expireAt) {}
```

`engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java`:
```java
package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 动态 DB 只读凭证：现场 CREATE USER + GRANT SELECT（裸 JDBC，因 ORM 不做账号 DDL），
 * 登记 Jimmer 租约；撤销时 DROP USER。用户名/密码用十六进制（仅 [0-9a-f]）避免标识符注入。
 */
public final class DynamicDbCredentials {

    private final Connection admin;
    private final LeaseManager leases;
    private final String jdbcUrl;
    private final SecureRandom random = new SecureRandom();

    public DynamicDbCredentials(Connection admin, LeaseManager leases, String jdbcUrl) {
        this.admin = admin;
        this.leases = leases;
        this.jdbcUrl = jdbcUrl;
    }

    public IssuedCred issueReadonly(String schema, Duration ttl) {
        String user = "v_ro_" + randomHex(6);
        String pwd = randomHex(16);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + pwd + "'");
            st.execute("GRANT SELECT ON `" + schema + "`.* TO '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("issue readonly cred failed", e);
        }
        Lease lease = leases.register("db/creds/" + schema + "-ro", ttl, l -> dropUser(user));
        return new IssuedCred(user, pwd, lease.leaseId(), lease.expireAt());
    }

    public void revoke(String leaseId) {
        leases.revoke(leaseId);   // 触发 Revoker → dropUser
    }

    private void dropUser(String user) {
        try (Statement st = admin.createStatement()) {
            st.execute("DROP USER IF EXISTS '" + user + "'@'%'");
            st.execute("FLUSH PRIVILEGES");
        } catch (Exception e) {
            throw new IllegalStateException("drop user failed: " + user, e);
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DynamicDbCredentialsIT`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 运行全部 engine 测试，确认无回归**

Run: `mvn -B -pl engine verify`
Expected: 计划 1（crypto/barrier/shamir/seal，surefire 单测）+ 计划 2（storage/sealstore/audit/lease/creds，failsafe `*IT`）全部 PASS。
> 注意：`mvn test` 默认**不**拾取 `*IT`（surefire 的默认 include 不含 IT），必须用 `verify` 触发 failsafe；单个 IT 调试可用 `mvn -pl engine test -Dtest=XxxIT`（按类名强制 surefire 跑）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/secrets engine/src/test/java/io/custos/engine/secrets
git commit -m "feat(engine): dynamic MySQL read-only credentials (raw JDBC user mgmt + Jimmer lease)"
```

---

## Self-Review（对照 spec §3.4–3.6、§4、ADR-8）

- **Spec 覆盖**：Storage(§3.4 → Jimmer)→Task 1；SealStore→Task 2；哈希链审计(§3.6)→Task 3；Lease(§3.5)→Task 4；动态 DB 凭证(详设 06 §3)→Task 5。
- **ADR-8 落地**：自身元数据表（storage/seal_config/audit/lease）全部 **Jimmer 实体 + JSqlClient**；**裸 JDBC 仅用于** Task 5 的 `CREATE/DROP USER`（账号 DDL）。加密边界：`StorageEntry.value`/`SealConfigEntry.value` 存 Barrier 密文，加解密在 `JimmerStorage`/service 层，Jimmer 不接触明文（Task 1 断言密文）。
- **类型一致性**：`Storage`、`SealStore`（计划 1）、`AuditLog`/`AuditRecord`/`VerifyResult`、`LeaseManager`/`Lease`/`Revoker`、`DynamicDbCredentials`/`IssuedCred` 接口与计划 1/3/5 一致；Jimmer 生成类 `XxxDraft`/`XxxTable` 由 jimmer-apt 编译时产生。
- **占位扫描**：无 TODO/TBD。两处注释说明（残缺对象保存语义、Jimmer 生成类）属机制解释，非缺失。
- **Jimmer API 准确性**（据 Jimmer 0.10.10 源码 `SaveOperations`/`Entities` + save-mode 文档核准）：`JSqlClient.newBuilder().setConnectionManager(ConnectionManager.simpleConnectionManager(ds)).setDialect(new MySqlDialect())`；`getEntities().findById(Class,id)` / `delete(Class,id)`（**非 `deleteById`**）/ `save(e)`（默认 UPSERT）/ `saveCommand(e).setMode(SaveMode.X).execute()`；`createQuery(Table.$).where(...).select(...).execute()`、`XxxDraft.$.produce(...)`；列名用 `@Column` 避开 KEY/VALUE 保留字。
- **SaveMode 正确性（深度学习后修正的真实 bug）**：① 审计行无 @Id(自增)/@Key → **INSERT_ONLY**（默认 UPSERT 会抛 "neither id nor key"）；② lease 续约/吊销为残缺对象部分更新 → **UPDATE_ONLY**（避免 UPSERT 的 `insert...on duplicate key` 触发 NOT NULL）；③ storage/seal put 全列已设、按 @Id → UPSERT(`save`) 正确。详见 `docs/research/jimmer.md` §2.7。
- **可独立交付**：本计划 + 计划 1 = 可单测/集成测试的完整引擎（Jimmer 持久化 + 加密/解封/审计/租约/动态凭证）。

> **下一计划**：3/5 身份层（JWT）——不变（不依赖持久化）。
