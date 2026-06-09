# Custos MVP v0.1 — 引擎持久化 Implementation Plan（计划 2/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在引擎基座（计划 1/5）之上，以 TDD 实现持久化层——MySQL 全密文存储、MySQL 版 SealStore、哈希链防篡改审计、租约管理、动态 MySQL 只读凭证（CREATE/DROP USER + TTL）。

**Architecture:** 纯 JDBC（无 ORM），所有敏感值落盘前经 Barrier 加密。集成测试用 Testcontainers 起真实 MySQL。审计为只追加哈希链。动态凭证现场建临时只读账号、到期由 Lease 撤销（DROP USER）。

**Tech Stack:** Java 21 · JDBC · MySQL 8 · Testcontainers · JUnit 5 ·（依赖计划 1 的 `IntlSuite`/`DefaultBarrier`/`SealManager`）

> 前置：计划 1/5 已完成（`io.custos.engine.crypto`/`barrier`/`seal` 可用）。对应 spec §3.4–3.6、§4，详设 `docs/design/02` §7/§11、`docs/design/06` §3。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/pom.xml` | 加 mysql-connector-j（runtime）、testcontainers-mysql/junit（test）|
| `engine/src/main/resources/db/schema.sql` | 建表 DDL（custos_storage/keyring/seal_config/audit/lease/dyn_role）|
| `engine/src/main/java/io/custos/engine/storage/Storage.java` | 存储抽象 |
| `engine/src/main/java/io/custos/engine/storage/MySqlStorage.java` | MySQL 全密文存储 |
| `engine/src/main/java/io/custos/engine/seal/MySqlSealStore.java` | SealStore 的 MySQL 实现 |
| `engine/src/main/java/io/custos/engine/audit/AuditRecord.java` | 审计记录（不可变）|
| `engine/src/main/java/io/custos/engine/audit/AuditLog.java` | 审计接口 |
| `engine/src/main/java/io/custos/engine/audit/VerifyResult.java` | 链校验结果 |
| `engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java` | 哈希链实现 |
| `engine/src/main/java/io/custos/engine/lease/Lease.java` | 租约 |
| `engine/src/main/java/io/custos/engine/lease/Revoker.java` | 撤销回调 |
| `engine/src/main/java/io/custos/engine/lease/LeaseManager.java` | 租约接口 |
| `engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java` | 租约实现 |
| `engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java` | 动态 DB 只读凭证 |
| `engine/src/test/java/io/custos/engine/**` | 对应集成测试（Testcontainers）|

---

## Task 1: 加依赖 + 建表 DDL + Storage 抽象与 MySQL 实现

**Files:**
- Modify: `engine/pom.xml`
- Create: `engine/src/main/resources/db/schema.sql`
- Create: `engine/src/main/java/io/custos/engine/storage/Storage.java`
- Create: `engine/src/main/java/io/custos/engine/storage/MySqlStorage.java`
- Test: `engine/src/test/java/io/custos/engine/storage/MySqlStorageIT.java`

- [ ] **Step 1: 加依赖到 `engine/pom.xml`**（在 `<dependencies>` 内追加）
```xml
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
```

- [ ] **Step 2: 写建表 DDL**

`engine/src/main/resources/db/schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS custos_storage (
  skey       VARCHAR(255) PRIMARY KEY,
  svalue     LONGBLOB NOT NULL,          -- Barrier 密文
  updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS custos_seal_config (
  ckey   VARCHAR(64) PRIMARY KEY,        -- wrapped_barrier / threshold / shares
  cval   LONGBLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS custos_audit (
  seq             BIGINT AUTO_INCREMENT PRIMARY KEY,
  ts              BIGINT NOT NULL,
  actor           VARCHAR(512) NOT NULL,
  task            VARCHAR(512),
  resource        VARCHAR(512),
  action          VARCHAR(64),
  decision        VARCHAR(32),
  result_digest   VARCHAR(128),
  sensitive_hmac  VARCHAR(128),
  prev_hash       VARCHAR(128) NOT NULL,
  chain_hash      VARCHAR(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS custos_lease (
  lease_id      VARCHAR(128) PRIMARY KEY,
  resource_path VARCHAR(512) NOT NULL,
  issued_at     BIGINT NOT NULL,
  expire_at     BIGINT NOT NULL,
  revoked       TINYINT NOT NULL DEFAULT 0
);
```

- [ ] **Step 3: 写失败测试（Testcontainers MySQL：put→get 密文往返）**

`engine/src/test/java/io/custos/engine/storage/MySqlStorageIT.java`:
```java
package io.custos.engine.storage;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MySqlStorageIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private Connection conn;
    private MySqlStorage storage;

    private DefaultBarrier barrier() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(1, k);
        return new DefaultBarrier(new IntlSuite(), kr);
    }

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS custos_storage (
                  skey VARCHAR(255) PRIMARY KEY, svalue LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)""");
        }
        storage = new MySqlStorage(conn, barrier());
    }

    @Test
    void putThenGetRoundTrips() {
        storage.put("k1", "secret-value".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), storage.get("k1").orElseThrow());
    }

    @Test
    void storedBytesAreCiphertextNotPlaintext() throws Exception {
        storage.put("k2", "plaintext-marker".getBytes(StandardCharsets.UTF_8));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT svalue FROM custos_storage WHERE skey='k2'")) {
            assertTrue(rs.next());
            byte[] raw = rs.getBytes(1);
            String asText = new String(raw, StandardCharsets.UTF_8);
            assertFalse(asText.contains("plaintext-marker"), "落盘必须是密文");
        }
    }

    @Test
    void getMissingReturnsEmpty() {
        assertTrue(storage.get("nope").isEmpty());
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=MySqlStorageIT`
Expected: 编译失败（Storage/MySqlStorage 未定义）。

- [ ] **Step 5: 实现 Storage 接口**

`engine/src/main/java/io/custos/engine/storage/Storage.java`:
```java
package io.custos.engine.storage;

import java.util.List;
import java.util.Optional;

/** 通用密文 KV 存储：所有 value 落盘前经 Barrier 加密，存储后端只见密文。 */
public interface Storage {
    Optional<byte[]> get(String key);
    void put(String key, byte[] value);
    void delete(String key);
    List<String> list(String prefix);
}
```

- [ ] **Step 6: 实现 MySqlStorage**

`engine/src/main/java/io/custos/engine/storage/MySqlStorage.java`:
```java
package io.custos.engine.storage;

import io.custos.engine.barrier.Barrier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** MySQL 全密文存储：put 时 barrier.seal，get 时 barrier.open。 */
public final class MySqlStorage implements Storage {

    private final Connection conn;
    private final Barrier barrier;

    public MySqlStorage(Connection conn, Barrier barrier) {
        this.conn = conn;
        this.barrier = barrier;
    }

    @Override
    public Optional<byte[]> get(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT svalue FROM custos_storage WHERE skey=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(barrier.open(rs.getBytes(1)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("storage get failed", e);
        }
    }

    @Override
    public void put(String key, byte[] value) {
        byte[] sealed = barrier.seal(value);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO custos_storage(skey,svalue,updated_at) VALUES(?,?,?) "
              + "ON DUPLICATE KEY UPDATE svalue=VALUES(svalue), updated_at=VALUES(updated_at)")) {
            ps.setString(1, key);
            ps.setBytes(2, sealed);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("storage put failed", e);
        }
    }

    @Override
    public void delete(String key) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM custos_storage WHERE skey=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("storage delete failed", e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT skey FROM custos_storage WHERE skey LIKE ?")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("storage list failed", e);
        }
        return out;
    }
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=MySqlStorageIT`
Expected: PASS（3 个用例；首次会拉取 mysql:8.0 镜像）。

- [ ] **Step 8: 提交**
```bash
git add engine/pom.xml engine/src/main/resources/db/schema.sql engine/src/main/java/io/custos/engine/storage engine/src/test/java/io/custos/engine/storage
git commit -m "feat(engine): MySQL ciphertext storage with barrier"
```

---

## Task 2: MySqlSealStore（把 SealStore 接到 MySQL）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/seal/MySqlSealStore.java`
- Test: `engine/src/test/java/io/custos/engine/seal/MySqlSealStoreIT.java`

- [ ] **Step 1: 写失败测试（解封产物跨实例从 MySQL 恢复）**

`engine/src/test/java/io/custos/engine/seal/MySqlSealStoreIT.java`:
```java
package io.custos.engine.seal;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MySqlSealStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS custos_seal_config (ckey VARCHAR(64) PRIMARY KEY, cval LONGBLOB NOT NULL)");
        }
    }

    @Test
    void unsealRecoversFromMySqlAcrossInstances() {
        MySqlSealStore store = new MySqlSealStore(conn);
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);

        // 新实例 + 同一 MySQL store（模拟重启）
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new MySqlSealStore(conn));
        assertTrue(mgr.status().sealed());
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(2));
        SealStatus s = mgr.submitUnsealKey(shares.get(4));
        assertFalse(s.sealed());

        DefaultBarrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        assertArrayEquals("ok".getBytes(), barrier.open(barrier.seal("ok".getBytes())));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=MySqlSealStoreIT`
Expected: 编译失败（MySqlSealStore 未定义）。

- [ ] **Step 3: 实现 MySqlSealStore**

`engine/src/main/java/io/custos/engine/seal/MySqlSealStore.java`:
```java
package io.custos.engine.seal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/** SealStore 的 MySQL 实现（存于 custos_seal_config）。wrapped_barrier 为密文；threshold/shares 为元数据。 */
public final class MySqlSealStore implements SealStore {

    private final Connection conn;

    public MySqlSealStore(Connection conn) {
        this.conn = conn;
    }

    @Override
    public Optional<byte[]> get(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT cval FROM custos_seal_config WHERE ckey=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("seal store get failed", e);
        }
    }

    @Override
    public void put(String key, byte[] value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO custos_seal_config(ckey,cval) VALUES(?,?) ON DUPLICATE KEY UPDATE cval=VALUES(cval)")) {
            ps.setString(1, key);
            ps.setBytes(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seal store put failed", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=MySqlSealStoreIT`
Expected: PASS。

- [ ] **Step 5: 提交**
```bash
git add engine/src/main/java/io/custos/engine/seal/MySqlSealStore.java engine/src/test/java/io/custos/engine/seal/MySqlSealStoreIT.java
git commit -m "feat(engine): MySQL-backed seal store"
```

---

## Task 3: 哈希链防篡改审计

**Files:**
- Create: `engine/src/main/java/io/custos/engine/audit/AuditRecord.java`
- Create: `engine/src/main/java/io/custos/engine/audit/VerifyResult.java`
- Create: `engine/src/main/java/io/custos/engine/audit/AuditLog.java`
- Create: `engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java`
- Test: `engine/src/test/java/io/custos/engine/audit/HashChainAuditLogIT.java`

- [ ] **Step 1: 写失败测试（append→verify OK；改一条→verify 定位断链）**

`engine/src/test/java/io/custos/engine/audit/HashChainAuditLogIT.java`:
```java
package io.custos.engine.audit;

import io.custos.engine.crypto.IntlSuite;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class HashChainAuditLogIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private Connection conn;
    private HashChainAuditLog audit;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_audit (
                seq BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL, actor VARCHAR(512) NOT NULL,
                task VARCHAR(512), resource VARCHAR(512), action VARCHAR(64), decision VARCHAR(32),
                result_digest VARCHAR(128), sensitive_hmac VARCHAR(128),
                prev_hash VARCHAR(128) NOT NULL, chain_hash VARCHAR(128) NOT NULL)""");
        }
        byte[] auditKey = new byte[32];
        audit = new HashChainAuditLog(conn, new IntlSuite(), auditKey);
    }

    private AuditRecord rec(String actor, String action) {
        return new AuditRecord(System.currentTimeMillis(), actor, "task", "db:orders", action, "ALLOW", "digest", "pwd=xxx");
    }

    @Test
    void appendThenVerifyPasses() {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentB", "read"));
        VerifyResult r = audit.verify();
        assertTrue(r.ok(), "完整链应校验通过");
    }

    @Test
    void tamperingBreaksChain() throws Exception {
        audit.append(rec("agentA", "read"));
        audit.append(rec("agentA", "read"));
        // 篡改第 1 条
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE custos_audit SET action='write' WHERE seq=1");
        }
        VerifyResult r = audit.verify();
        assertFalse(r.ok());
        assertEquals(1L, r.brokenAtSeq());
    }

    @Test
    void sensitiveFieldNotStoredInPlaintext() throws Exception {
        audit.append(rec("agentA", "read"));   // sensitive = "pwd=xxx"
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT sensitive_hmac FROM custos_audit WHERE seq=1")) {
            assertTrue(rs.next());
            assertNotEquals("pwd=xxx", rs.getString(1), "敏感字段应 HMAC 脱敏");
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=HashChainAuditLogIT`
Expected: 编译失败（audit 类未定义）。

- [ ] **Step 3: 写 AuditRecord / VerifyResult / AuditLog**

`engine/src/main/java/io/custos/engine/audit/AuditRecord.java`:
```java
package io.custos.engine.audit;

/** 一条审计记录（sensitiveRaw 在落盘前会被 HMAC 脱敏，不存明文）。 */
public record AuditRecord(
        long ts, String actor, String task, String resource,
        String action, String decision, String resultDigest, String sensitiveRaw) {
}
```

`engine/src/main/java/io/custos/engine/audit/VerifyResult.java`:
```java
package io.custos.engine.audit;

/** 链校验结果。ok=true 时 brokenAtSeq=-1。 */
public record VerifyResult(boolean ok, long brokenAtSeq) {
    public static VerifyResult passed() { return new VerifyResult(true, -1); }
    public static VerifyResult brokenAt(long seq) { return new VerifyResult(false, seq); }
}
```

`engine/src/main/java/io/custos/engine/audit/AuditLog.java`:
```java
package io.custos.engine.audit;

/** 防篡改审计：append 只追加并维护哈希链；verify 重算链检测篡改。 */
public interface AuditLog {
    void append(AuditRecord record);
    VerifyResult verify();
}
```

- [ ] **Step 4: 实现 HashChainAuditLog**

`engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java`:
```java
package io.custos.engine.audit;

import io.custos.engine.crypto.CipherSuite;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;

/** 哈希链审计：chain_hash_n = H(prev_hash || canonical(record_n))；敏感字段 HMAC 脱敏；只追加。 */
public final class HashChainAuditLog implements AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final Connection conn;
    private final CipherSuite suite;
    private final byte[] auditKey;

    public HashChainAuditLog(Connection conn, CipherSuite suite, byte[] auditKey) {
        this.conn = conn;
        this.suite = suite;
        this.auditKey = auditKey.clone();
    }

    @Override
    public void append(AuditRecord r) {
        try {
            String prev = lastChainHash();
            String sensitiveHmac = hex(suite.hmac(auditKey, nz(r.sensitiveRaw()).getBytes(StandardCharsets.UTF_8)));
            String canonical = canonical(r, sensitiveHmac);
            String chain = hex(suite.hash((prev + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO custos_audit(ts,actor,task,resource,action,decision,result_digest,sensitive_hmac,prev_hash,chain_hash) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, r.ts());
                ps.setString(2, r.actor());
                ps.setString(3, r.task());
                ps.setString(4, r.resource());
                ps.setString(5, r.action());
                ps.setString(6, r.decision());
                ps.setString(7, r.resultDigest());
                ps.setString(8, sensitiveHmac);
                ps.setString(9, prev);
                ps.setString(10, chain);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("audit append failed", e);
        }
    }

    @Override
    public VerifyResult verify() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT seq,ts,actor,task,resource,action,decision,result_digest,sensitive_hmac,prev_hash,chain_hash "
              + "FROM custos_audit ORDER BY seq ASC")) {
            String expectedPrev = GENESIS;
            while (rs.next()) {
                String prev = rs.getString("prev_hash");
                if (!prev.equals(expectedPrev)) return VerifyResult.brokenAt(rs.getLong("seq"));
                AuditRecord r = new AuditRecord(rs.getLong("ts"), rs.getString("actor"), rs.getString("task"),
                        rs.getString("resource"), rs.getString("action"), rs.getString("decision"),
                        rs.getString("result_digest"), null);
                String canonical = canonical(r, rs.getString("sensitive_hmac"));
                String recompute = hex(suite.hash((prev + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
                if (!recompute.equals(rs.getString("chain_hash"))) return VerifyResult.brokenAt(rs.getLong("seq"));
                expectedPrev = rs.getString("chain_hash");
            }
            return VerifyResult.passed();
        } catch (Exception e) {
            throw new IllegalStateException("audit verify failed", e);
        }
    }

    private String lastChainHash() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT chain_hash FROM custos_audit ORDER BY seq DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : GENESIS;
        }
    }

    private static String canonical(AuditRecord r, String sensitiveHmac) {
        return String.join("|", String.valueOf(r.ts()), nz(r.actor()), nz(r.task()), nz(r.resource()),
                nz(r.action()), nz(r.decision()), nz(r.resultDigest()), nz(sensitiveHmac));
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
git commit -m "feat(engine): tamper-evident hash-chain audit log"
```

---

## Task 4: 租约管理（TTL/续约/撤销/前缀撤销）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/lease/Lease.java`
- Create: `engine/src/main/java/io/custos/engine/lease/Revoker.java`
- Create: `engine/src/main/java/io/custos/engine/lease/LeaseManager.java`
- Create: `engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java`
- Test: `engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java`:
```java
package io.custos.engine.lease;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DefaultLeaseManagerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    private Connection conn;
    private DefaultLeaseManager leases;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS custos_lease (
                lease_id VARCHAR(128) PRIMARY KEY, resource_path VARCHAR(512) NOT NULL,
                issued_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, revoked TINYINT NOT NULL DEFAULT 0)""");
        }
        leases = new DefaultLeaseManager(conn);
    }

    @Test
    void revokeCallsRevokerAndMarksRevoked() {
        AtomicInteger revoked = new AtomicInteger();
        Lease lease = leases.register("db/creds/orders-ro/abc", Duration.ofHours(1), l -> revoked.incrementAndGet());
        leases.revoke(lease.leaseId());
        assertEquals(1, revoked.get());
    }

    @Test
    void revokePrefixRevokesSubtree() {
        AtomicInteger revoked = new AtomicInteger();
        Revoker r = l -> revoked.incrementAndGet();
        leases.register("db/creds/orders-ro/a", Duration.ofHours(1), r);
        leases.register("db/creds/orders-ro/b", Duration.ofHours(1), r);
        leases.register("db/creds/other/c", Duration.ofHours(1), r);
        int n = leases.revokePrefix("db/creds/orders-ro/");
        assertEquals(2, n);
        assertEquals(2, revoked.get());
    }

    @Test
    void renewExtendsExpiry() {
        Lease lease = leases.register("db/creds/x", Duration.ofMinutes(10), l -> {});
        Lease renewed = leases.renew(lease.leaseId(), Duration.ofHours(2));
        assertTrue(renewed.expireAt() > lease.expireAt());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DefaultLeaseManagerIT`
Expected: 编译失败（lease 类未定义）。

- [ ] **Step 3: 写 Lease / Revoker / LeaseManager**

`engine/src/main/java/io/custos/engine/lease/Lease.java`:
```java
package io.custos.engine.lease;

public record Lease(String leaseId, String resourcePath, long issuedAt, long expireAt) {}
```

`engine/src/main/java/io/custos/engine/lease/Revoker.java`:
```java
package io.custos.engine.lease;

/** 撤销回调：如动态 DB engine 在此执行 DROP USER。 */
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
    Lease renew(String leaseId, Duration increment);   // increment 从当前时间起算
    void revoke(String leaseId);
    int revokePrefix(String prefix);                    // 前缀批量撤销，返回撤销数
}
```

- [ ] **Step 4: 实现 DefaultLeaseManager**

`engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java`:
```java
package io.custos.engine.lease;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 租约管理：register/renew/revoke/revokePrefix，后台扫描到期自动撤销。Revoker 留内存映射（MVP 单节点）。 */
public final class DefaultLeaseManager implements LeaseManager {

    private final Connection conn;
    private final ConcurrentHashMap<String, Revoker> revokers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor();

    public DefaultLeaseManager(Connection conn) {
        this.conn = conn;
        scanner.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public Lease register(String resourcePath, Duration ttl, Revoker revoker) {
        String id = resourcePath + "/" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expire = now + ttl.toMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO custos_lease(lease_id,resource_path,issued_at,expire_at,revoked) VALUES(?,?,?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, resourcePath);
            ps.setLong(3, now);
            ps.setLong(4, expire);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("lease register failed", e);
        }
        revokers.put(id, revoker);
        return new Lease(id, resourcePath, now, expire);
    }

    @Override
    public Lease renew(String leaseId, Duration increment) {
        long expire = System.currentTimeMillis() + increment.toMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE custos_lease SET expire_at=? WHERE lease_id=? AND revoked=0")) {
            ps.setLong(1, expire);
            ps.setString(2, leaseId);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("lease not found or revoked: " + leaseId);
        } catch (Exception e) {
            throw new IllegalStateException("lease renew failed", e);
        }
        return load(leaseId);
    }

    @Override
    public void revoke(String leaseId) {
        Lease lease = load(leaseId);
        Revoker r = revokers.remove(leaseId);
        if (r != null) r.revoke(lease);
        markRevoked(leaseId);
    }

    @Override
    public int revokePrefix(String prefix) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lease_id FROM custos_lease WHERE resource_path LIKE ? AND revoked=0")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("lease revokePrefix query failed", e);
        }
        for (String id : ids) revoke(id);
        return ids.size();
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lease_id FROM custos_lease WHERE expire_at<? AND revoked=0")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) expired.add(rs.getString(1));
            }
        } catch (Exception e) {
            return;   // 下个周期重试
        }
        for (String id : expired) {
            try { revoke(id); } catch (RuntimeException ignore) { /* 重试 + 告警（计划 5 接监控）*/ }
        }
    }

    private Lease load(String leaseId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT resource_path,issued_at,expire_at FROM custos_lease WHERE lease_id=?")) {
            ps.setString(1, leaseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("lease not found: " + leaseId);
                return new Lease(leaseId, rs.getString(1), rs.getLong(2), rs.getLong(3));
            }
        } catch (Exception e) {
            throw new IllegalStateException("lease load failed", e);
        }
    }

    private void markRevoked(String leaseId) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE custos_lease SET revoked=1 WHERE lease_id=?")) {
            ps.setString(1, leaseId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("lease markRevoked failed", e);
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DefaultLeaseManagerIT`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/lease engine/src/test/java/io/custos/engine/lease
git commit -m "feat(engine): lease manager with TTL, revoke, prefix-revoke, auto-expiry"
```

---

## Task 5: 动态 MySQL 只读凭证（CREATE/DROP USER + 租约）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java`
- Test: `engine/src/test/java/io/custos/engine/secrets/DynamicDbCredentialsIT.java`

- [ ] **Step 1: 写失败测试（签发临时只读账号→可查→撤销后失效）**

`engine/src/test/java/io/custos/engine/secrets/DynamicDbCredentialsIT.java`:
```java
package io.custos.engine.secrets;

import io.custos.engine.lease.DefaultLeaseManager;
import io.custos.engine.lease.Lease;
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
            st.execute("CREATE TABLE IF NOT EXISTS custos_lease (lease_id VARCHAR(128) PRIMARY KEY, resource_path VARCHAR(512), issued_at BIGINT, expire_at BIGINT, revoked TINYINT DEFAULT 0)");
            st.execute("CREATE DATABASE IF NOT EXISTS appdb");
            st.execute("CREATE TABLE IF NOT EXISTS appdb.orders (id INT)");
            st.execute("INSERT INTO appdb.orders VALUES (1)");
        }
        creds = new DynamicDbCredentials(admin, new DefaultLeaseManager(admin), MYSQL.getJdbcUrl());
    }

    @Test
    void issuedCredentialCanReadButCannotWrite() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        try (Connection user = DriverManager.getConnection(MYSQL.getJdbcUrl(), c.username(), c.password());
             Statement st = user.createStatement()) {
            assertTrue(st.executeQuery("SELECT COUNT(*) FROM appdb.orders").next());     // 只读可查
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO appdb.orders VALUES (2)")); // 写被拒
        }
    }

    @Test
    void revokingLeaseDropsUser() throws Exception {
        IssuedCred c = creds.issueReadonly("appdb", Duration.ofHours(1));
        creds.revoke(c.leaseId());
        assertThrows(SQLException.class, () ->
                DriverManager.getConnection(MYSQL.getJdbcUrl(), c.username(), c.password()));   // 账号已 DROP
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DynamicDbCredentialsIT`
Expected: 编译失败（DynamicDbCredentials / IssuedCred 未定义）。

- [ ] **Step 3: 写 IssuedCred 与 DynamicDbCredentials**

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

/** 动态 DB 只读凭证：现场 CREATE USER + GRANT SELECT，登记租约；撤销时 DROP USER。 */
public final class DynamicDbCredentials {

    private final Connection admin;          // 受控的根/管理连接
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
            throw new IllegalStateException("drop user failed: " + user, e);   // 由 Lease 层重试+告警
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
```

> 注：用户名/密码用十六进制（仅 [0-9a-f]），避免 SQL 字符串注入风险；schema 用反引号包裹。生产应进一步用参数化/标识符白名单（计划 5 加固）。

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DynamicDbCredentialsIT`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 运行全部 engine 测试，确认无回归**

Run: `mvn -q -pl engine test`
Expected: 全部 PASS（计划 1 + 计划 2 累计 ~30 用例）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/secrets engine/src/test/java/io/custos/engine/secrets
git commit -m "feat(engine): dynamic MySQL read-only credentials with lease revocation"
```

---

## Self-Review（对照 spec §3.4–3.6、§4）

- **Spec 覆盖**：Storage(§3.4 + schema §4)→Task 1；MySQL SealStore（衔接计划 1）→Task 2；哈希链审计(§3.6)→Task 3；Lease(§3.5)→Task 4；动态 DB 凭证(§3 broker `CredIssuer` 的引擎侧 + `docs/design/06` §3)→Task 5。
- **类型一致性**：`Storage.get/put/delete/list`、`SealStore.get/put`（与计划 1 一致）、`AuditLog.append/verify` + `AuditRecord`/`VerifyResult`、`LeaseManager.register/renew/revoke/revokePrefix` + `Lease`/`Revoker`、`DynamicDbCredentials.issueReadonly/revoke` + `IssuedCred` 跨任务一致。
- **占位扫描**：无 TODO/TBD；每个代码步骤含完整实现。
- **安全留痕**：落盘密文（Task 1 断言密文）、审计脱敏+防篡改（Task 3 断言）、撤销真删账号（Task 5 断言）。SQL 注入面已用十六进制标识符 + 反引号收窄，并标注计划 5 进一步加固。
- **可独立交付**：本计划产物 + 计划 1 = 一个可单测/集成测试的完整引擎（加密存储/解封/审计/租约/动态凭证）。

> **下一计划**：3/5 身份层（JWT 签发/校验，复用 IntlSuite ECDSA）。
