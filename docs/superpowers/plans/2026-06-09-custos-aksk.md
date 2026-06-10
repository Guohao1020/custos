# Custos AK·SK secrets engine + 轮换（M09）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SecretsEngine SPI 提供第二实现 `AkSkSecretsEngine`：内存 Provider 动态签发 AK·SK、复用 LeaseManager 做 TTL/撤销、rotate 支持 grace 多版本过渡。

**Architecture:** `AkSkProvider` SPI（内存模拟实现，真实云留缝）现场铸/吊销 AK·SK；`AkSkSecretsEngine` 用 `LeaseManager` 登记租约（到期/撤销→吊销 AK），返回 `IssuedCred`（AK=username、SK=password）；rotate 发新一份后按 grace 立即撤旧或续旧到 grace。

**Tech Stack:** Java 21 · JUnit 5（沿用 engine 现有依赖，无新依赖）

> 前置：engine.secrets（SecretsEngine/SecretsEngineRegistry/IssuedCred 已存在，PF-T1）+ engine.lease（LeaseManager/Lease/Revoker 已存在，M02）。对应 spec `docs/superpowers/specs/2026-06-09-custos-aksk-design.md`。
> **纯单元、无 Docker**：AkSkSecretsEngine 测试用内存 `LeaseManager` 替身（避开 DefaultLeaseManager 的 MySQL 依赖；真实租约 DB 行为已由 M02 `DefaultLeaseManagerIT` 覆盖）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/src/main/java/io/custos/engine/secrets/AkSkPair.java` | (accessKeyId, secretKey) |
| `engine/src/main/java/io/custos/engine/secrets/AkSkProvider.java` | 后端 SPI（mint/revoke）|
| `engine/src/main/java/io/custos/engine/secrets/InMemoryAkSkProvider.java` | 内存模拟实现 + isActive |
| `engine/src/main/java/io/custos/engine/secrets/AkSkSecretsEngine.java` | SecretsEngine 第二实现（issue/revoke/rotate）|
| `engine/src/test/java/io/custos/engine/secrets/InMemoryAkSkProviderTest.java` | Provider 测试 |
| `engine/src/test/java/io/custos/engine/secrets/AkSkSecretsEngineTest.java` | 引擎测试（含 FakeLeaseManager 替身）|

---

## Task 1: AkSkPair + AkSkProvider + InMemoryAkSkProvider

**Files:**
- Create: `engine/src/main/java/io/custos/engine/secrets/AkSkPair.java`
- Create: `engine/src/main/java/io/custos/engine/secrets/AkSkProvider.java`
- Create: `engine/src/main/java/io/custos/engine/secrets/InMemoryAkSkProvider.java`
- Test: `engine/src/test/java/io/custos/engine/secrets/InMemoryAkSkProviderTest.java`

- [ ] **Step 1: 写 AkSkPair + AkSkProvider SPI**

`engine/src/main/java/io/custos/engine/secrets/AkSkPair.java`:
```java
package io.custos.engine.secrets;

/** 云访问密钥对。 */
public record AkSkPair(String accessKeyId, String secretKey) {}
```

`engine/src/main/java/io/custos/engine/secrets/AkSkProvider.java`:
```java
package io.custos.engine.secrets;

/** AK·SK 后端 SPI：现场铸/吊销。未来加 AwsStsProvider / AliyunRamProvider。 */
public interface AkSkProvider {
    AkSkPair mint(String mount);
    void revoke(String accessKeyId);
}
```

- [ ] **Step 2: 写失败测试**

`engine/src/test/java/io/custos/engine/secrets/InMemoryAkSkProviderTest.java`:
```java
package io.custos.engine.secrets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAkSkProviderTest {

    @Test
    void mintProducesActiveDistinctPairs() {
        InMemoryAkSkProvider p = new InMemoryAkSkProvider();
        AkSkPair a = p.mint("aksk/app");
        AkSkPair b = p.mint("aksk/app");
        assertTrue(a.accessKeyId().startsWith("AKIA"));
        assertNotEquals(a.accessKeyId(), b.accessKeyId(), "AK 应唯一");
        assertFalse(a.secretKey().isBlank());
        assertTrue(p.isActive(a.accessKeyId()));
        assertTrue(p.isActive(b.accessKeyId()));
    }

    @Test
    void revokeDeactivates() {
        InMemoryAkSkProvider p = new InMemoryAkSkProvider();
        AkSkPair a = p.mint("aksk/app");
        p.revoke(a.accessKeyId());
        assertFalse(p.isActive(a.accessKeyId()));
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=InMemoryAkSkProviderTest`
Expected: 编译失败（InMemoryAkSkProvider 未定义）。

- [ ] **Step 4: 写 InMemoryAkSkProvider**

`engine/src/main/java/io/custos/engine/secrets/InMemoryAkSkProvider.java`:
```java
package io.custos.engine.secrets;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存模拟 AK·SK 后端（计划后续接真实 AWS STS / 阿里云 RAM 只换实现）。 */
public final class InMemoryAkSkProvider implements AkSkProvider {

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    @Override
    public AkSkPair mint(String mount) {
        String ak = "AKIA" + hex(6);   // 12 hex
        String sk = hex(16);           // 32 hex
        active.add(ak);
        return new AkSkPair(ak, sk);
    }

    @Override
    public void revoke(String accessKeyId) {
        active.remove(accessKeyId);
    }

    /** 供测试断言。 */
    public boolean isActive(String accessKeyId) {
        return active.contains(accessKeyId);
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=InMemoryAkSkProviderTest`
Expected: PASS（2 个用例）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/secrets/AkSkPair.java engine/src/main/java/io/custos/engine/secrets/AkSkProvider.java engine/src/main/java/io/custos/engine/secrets/InMemoryAkSkProvider.java engine/src/test/java/io/custos/engine/secrets/InMemoryAkSkProviderTest.java
git commit -m "feat(engine): AkSkProvider SPI + in-memory provider"
```

---

## Task 2: AkSkSecretsEngine（issue/revoke/rotate）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/secrets/AkSkSecretsEngine.java`
- Test: `engine/src/test/java/io/custos/engine/secrets/AkSkSecretsEngineTest.java`

- [ ] **Step 1: 写失败测试（含内存 LeaseManager 替身）**

`engine/src/test/java/io/custos/engine/secrets/AkSkSecretsEngineTest.java`:
```java
package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AkSkSecretsEngineTest {

    /** 内存 LeaseManager 替身（无 DB）：register/renew/revoke 维护内存状态并触发 Revoker。 */
    static final class FakeLeaseManager implements LeaseManager {
        final Map<String, Lease> leases = new HashMap<>();
        final Map<String, Revoker> revokers = new HashMap<>();
        final java.util.Set<String> renewed = new java.util.HashSet<>();
        int seq = 0;

        @Override
        public Lease register(String path, Duration ttl, Revoker r) {
            String id = path + "/" + (seq++);
            long now = System.currentTimeMillis();
            Lease l = new Lease(id, path, now, now + ttl.toMillis());
            leases.put(id, l);
            revokers.put(id, r);
            return l;
        }

        @Override
        public Lease renew(String leaseId, Duration inc) {
            renewed.add(leaseId);
            Lease old = leases.get(leaseId);
            long now = System.currentTimeMillis();
            Lease l = new Lease(leaseId, old.resourcePath(), old.issuedAt(), now + inc.toMillis());
            leases.put(leaseId, l);
            return l;
        }

        @Override
        public void revoke(String leaseId) {
            Revoker r = revokers.remove(leaseId);
            if (r != null) r.revoke(leases.get(leaseId));
        }

        @Override
        public int revokePrefix(String prefix) { return 0; }
    }

    @Test
    void typeIsAkSk() {
        assertEquals("ak-sk", new AkSkSecretsEngine(new InMemoryAkSkProvider(), new FakeLeaseManager()).type());
    }

    @Test
    void issueReturnsActiveCred() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred c = eng.issue("app", Duration.ofHours(1));
        assertTrue(c.username().startsWith("AKIA"));
        assertFalse(c.password().isBlank());
        assertTrue(provider.isActive(c.username()), "AK 应活跃");
    }

    @Test
    void revokeDeactivatesAk() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred c = eng.issue("app", Duration.ofHours(1));
        eng.revoke(c.leaseId());
        assertFalse(provider.isActive(c.username()), "撤销后 AK 应失效");
    }

    @Test
    void hardRotateRevokesOldImmediately() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred old = eng.issue("app", Duration.ofHours(1));
        IssuedCred fresh = eng.rotate(old.leaseId(), "app", Duration.ofHours(1), Duration.ZERO);
        assertFalse(provider.isActive(old.username()), "硬轮换：旧 AK 立即失效");
        assertTrue(provider.isActive(fresh.username()), "新 AK 活跃");
        assertNotEquals(old.leaseId(), fresh.leaseId(), "新旧 leaseId 不同");
    }

    @Test
    void graceRotateKeepsOldDuringWindow() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        FakeLeaseManager leases = new FakeLeaseManager();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, leases);
        IssuedCred old = eng.issue("app", Duration.ofHours(1));
        IssuedCred fresh = eng.rotate(old.leaseId(), "app", Duration.ofHours(1), Duration.ofMinutes(5));
        assertTrue(provider.isActive(old.username()), "grace 窗口内旧 AK 仍活跃");
        assertTrue(provider.isActive(fresh.username()), "新 AK 活跃");
        assertTrue(leases.renewed.contains(old.leaseId()), "旧租约被续到 grace");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=AkSkSecretsEngineTest`
Expected: 编译失败（AkSkSecretsEngine 未定义）。

- [ ] **Step 3: 写 AkSkSecretsEngine**

`engine/src/main/java/io/custos/engine/secrets/AkSkSecretsEngine.java`:
```java
package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;

import java.time.Duration;

/** AK·SK 动态凭证引擎（SecretsEngine 第二实现）：issue/revoke 复用租约，rotate 支持 grace 多版本过渡。 */
public final class AkSkSecretsEngine implements SecretsEngine {

    private final AkSkProvider provider;
    private final LeaseManager leases;

    public AkSkSecretsEngine(AkSkProvider provider, LeaseManager leases) {
        this.provider = provider;
        this.leases = leases;
    }

    @Override
    public String type() { return "ak-sk"; }

    @Override
    public IssuedCred issue(String path, Duration ttl) {
        AkSkPair p = provider.mint(path);
        Lease lease = leases.register("aksk/" + path, ttl, l -> provider.revoke(p.accessKeyId()));
        return new IssuedCred(p.accessKeyId(), p.secretKey(), lease.leaseId(), lease.expireAt());
    }

    @Override
    public void revoke(String leaseId) {
        leases.revoke(leaseId);
    }

    /** 轮换：发新一份；grace=0 立即撤旧（硬轮换），grace>0 把旧租约续到 grace（多版本过渡，到期后台自动撤旧）。 */
    public IssuedCred rotate(String oldLeaseId, String path, Duration newTtl, Duration grace) {
        IssuedCred fresh = issue(path, newTtl);
        if (grace.isZero()) {
            leases.revoke(oldLeaseId);
        } else {
            leases.renew(oldLeaseId, grace);
        }
        return fresh;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=AkSkSecretsEngineTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 运行 engine 全部单测，确认无回归**

Run: `mvn -pl engine test`
Expected: 计划 1 的 crypto/barrier/shamir/seal + SecretsEngineRegistry + AkSk 全部 PASS（engine 的 *IT 不被 surefire 拾取，单测全绿）。

- [ ] **Step 6: 提交**
```bash
git add engine/src/main/java/io/custos/engine/secrets/AkSkSecretsEngine.java engine/src/test/java/io/custos/engine/secrets/AkSkSecretsEngineTest.java
git commit -m "feat(engine): AkSkSecretsEngine (issue/revoke/rotate with grace) — SecretsEngine 2nd impl"
```

---

## Self-Review（对照 AK·SK 设计 spec）

- **Spec 覆盖**：AkSkPair/AkSkProvider/InMemoryAkSkProvider(§3)→Task1；AkSkSecretsEngine issue/revoke(§2/§3)→Task2；rotate grace 语义(§4)→Task2（hardRotate / graceRotate 两测）；复用 LeaseManager + IssuedCred(§2)→Task2；纯单元替身(§6 注)→FakeLeaseManager。全覆盖。
- **类型一致性**：`AkSkPair(accessKeyId,secretKey)`、`AkSkProvider.mint/revoke`、`InMemoryAkSkProvider.isActive`、`AkSkSecretsEngine(AkSkProvider,LeaseManager)/type/issue/revoke/rotate(String,String,Duration,Duration)`、复用 `IssuedCred(username,password,leaseId,expireAt)`、`LeaseManager.register/renew/revoke/revokePrefix`、`Lease(leaseId,resourcePath,issuedAt,expireAt)`、`Revoker.revoke(Lease)` 跨任务一致。
- **占位扫描**：无 TODO/TBD；代码步均完整。FakeLeaseManager 实现 LeaseManager 全部 4 方法。
- **secretless**：SK 仅在 IssuedCred 返回；引擎不打印/不入日志。
- **无新依赖**：engine 现有 JUnit；无 Docker（替身规避 MySQL）。

> **下一子项目**：M10 KV/更多 engine（依赖本 SPI 模式）/ M12 SPIFFE（依赖 OBO 已就绪）。
