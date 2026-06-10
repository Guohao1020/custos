# Custos 加固（M14）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GmSuite（国密 CipherSuite 第二实现）+ 内存清零系统化（Zeroize/Keyring.destroy）+ 压测 runbook 与 bench 标签用例 + 外部审计准备包。

**Architecture:** GmSuite 经 BouncyCastle（SM4/GCM、SM3、HmacSM3、SM2），封套格式与 Intl 相同、suiteId=0x02；`CipherSuite` 增 `default int keyLength()`（Intl 32 / Gm 16），`DefaultSealManager` 密钥长度改用之；Zeroize 集中清零 + Keyring 可销毁。审计与压测为文档/标签产物。

**Tech Stack:** Java 21 · bcprov-jdk18on 1.78.1（engine）· JUnit 5（`@Tag("bench")` 默认排除）

> 前置：M01（CipherSuite/IntlSuite/Barrier/SealManager）。对应 spec `docs/superpowers/specs/2026-06-10-custos-hardening-design.md`。
> BC 的 JCE 名称（"SM4/GCM/NoPadding"、"SM3"、"HmacSM3"、"SM3withSM2"、曲线 "sm2p256v1"）按 BC 1.78 文档；实现首测即核准，如名称有出入按 `NoSuchAlgorithmException` 实测修正（小风险）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/pom.xml` | 加 bcprov-jdk18on 1.78.1 |
| `engine/src/main/java/io/custos/engine/crypto/CipherSuite.java` | 加 `default int keyLength()` |
| `engine/src/main/java/io/custos/engine/crypto/GmSuite.java` | 国密套件（suiteId 0x02）|
| `engine/src/main/java/io/custos/engine/crypto/Zeroize.java` | 集中清零工具 |
| `engine/src/main/java/io/custos/engine/crypto/Keyring.java` | 加 `destroy()` |
| `engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java` | 密钥长度用 `suite.keyLength()` |
| `engine/src/test/java/io/custos/engine/crypto/{GmSuiteTest,ZeroizeTest}.java` + `seal/GmSealRoundTripTest.java` | TDD |
| `engine/src/test/java/io/custos/engine/bench/BarrierBenchSmokeTest.java` | `@Tag("bench")` |
| `docs/bench/RUNBOOK.md` · `docs/audit/AUDIT-PREP.md` | 压测/审计产物 |

---

## Task 1: GmSuite（TDD）+ keyLength 适配

- [ ] **Step 1: engine/pom.xml 加 `<dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk18on</artifactId><version>1.78.1</version></dependency>`**

- [ ] **Step 2: CipherSuite 加默认方法**
```java
    /** 对称密钥字节长度（AEAD key）。Intl=32(AES-256)，Gm=16(SM4)。 */
    default int keyLength() { return 32; }
```

- [ ] **Step 3: 写失败测试**

```java
package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class GmSuiteTest {
    private final GmSuite suite = new GmSuite();
    private byte[] key() { byte[] k = new byte[suite.keyLength()]; new SecureRandom().nextBytes(k); return k; }

    @Test void suiteIdAndKeyLength() {
        assertEquals((byte) 0x02, suite.suiteId());
        assertEquals(16, suite.keyLength());
    }
    @Test void sm4GcmRoundTripsAndRejectsTamper() {
        byte[] k = key();
        byte[] ct = suite.encrypt(k, "国密明文".getBytes(), "aad".getBytes());
        assertArrayEquals("国密明文".getBytes(), suite.decrypt(k, ct, "aad".getBytes()));
        ct[ct.length - 1] ^= 1;
        assertThrows(IntegrityException.class, () -> suite.decrypt(k, ct, "aad".getBytes()));
    }
    @Test void sm3DigestDeterministic32Bytes() {
        byte[] h1 = suite.hash("x".getBytes());
        assertEquals(32, h1.length);
        assertArrayEquals(h1, suite.hash("x".getBytes()));
    }
    @Test void hmacSm3KeyedAndDeterministic() {
        byte[] m1 = suite.hmac("k1".getBytes(), "d".getBytes());
        assertArrayEquals(m1, suite.hmac("k1".getBytes(), "d".getBytes()));
        assertFalse(java.util.Arrays.equals(m1, suite.hmac("k2".getBytes(), "d".getBytes())));
    }
    @Test void sm2SignVerifyAndWrongKeyRejected() {
        KeyPair kp = suite.genSignKey();
        byte[] sig = suite.sign(kp.getPrivate(), "msg".getBytes());
        assertTrue(suite.verify(kp.getPublic(), "msg".getBytes(), sig));
        assertFalse(suite.verify(suite.genSignKey().getPublic(), "msg".getBytes(), sig));
    }
}
```

- [ ] **Step 4: 实现 GmSuite（与 IntlSuite 同封套：nonce(12)||ct+tag；BC provider 静态注册）**

```java
package io.custos.engine.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** 国密套件（suiteId=0x02）：SM4-GCM / SM3 / HmacSM3 / SM2(SM3withSM2)。算法全部由 BouncyCastle 提供，绝不自写。 */
public final class GmSuite implements CipherSuite {

    static { if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider()); }
    private static final int NONCE = 12, TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();

    @Override public byte suiteId() { return 0x02; }
    @Override public int keyLength() { return 16; }   // SM4-128

    @Override
    public byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE]; random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) { throw new IllegalStateException("sm4 encrypt failed", e); }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE]; bb.get(nonce);
            byte[] ct = new byte[bb.remaining()]; bb.get(ct);
            Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) { throw new IntegrityException("sm4 decrypt/verify failed", e); }
    }

    @Override
    public byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SM3", "BC").digest(data); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSM3", "BC");
            m.init(new SecretKeySpec(key, "HmacSM3"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Override
    public KeyPair genSignKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
            g.initialize(new ECGenParameterSpec("sm2p256v1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Override
    public byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance("SM3withSM2", "BC");
            s.initSign(key); s.update(data); return s.sign();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Override
    public boolean verify(PublicKey key, byte[] data, byte[] sig) {
        try {
            Signature s = Signature.getInstance("SM3withSM2", "BC");
            s.initVerify(key); s.update(data); return s.verify(sig);
        } catch (GeneralSecurityException e) { return false; }
    }
}
```
> `IntegrityException` 若无 (String,Throwable) 构造器则补一个。

- [ ] **Step 5: DefaultSealManager 两处 `new byte[32]` 改 `new byte[suite.keyLength()]`（字段已有 suite）**

- [ ] **Step 6: 写 GmSeal 集成测试（GmSuite 跑通 init→3 片解封→Barrier 往返；内存 SealStore 同计划 1 的 InMemorySealStore 模式）**

```java
package io.custos.engine.seal;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.GmSuite;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GmSealRoundTripTest {
    static final class MemStore implements SealStore {
        final Map<String, byte[]> m = new HashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
    }

    @Test void gmSuiteSealUnsealBarrierRoundTrip() {
        GmSuite suite = new GmSuite();
        MemStore store = new MemStore();
        List<byte[]> shares = new DefaultSealManager(suite, store).init(5, 3);
        DefaultSealManager mgr = new DefaultSealManager(suite, store);
        mgr.submitUnsealKey(shares.get(0)); mgr.submitUnsealKey(shares.get(1));
        assertFalse(mgr.submitUnsealKey(shares.get(2)).sealed());
        DefaultBarrier barrier = new DefaultBarrier(suite, mgr.keyring());
        assertArrayEquals("国密ok".getBytes(), barrier.open(barrier.seal("国密ok".getBytes())));
    }
}
```

- [ ] **Step 7: 全绿（GmSuiteTest 5 + GmSealRoundTrip 1 + 既有回归）→ 提交** `feat(engine): GmSuite (SM4-GCM/SM3/HmacSM3/SM2) — CipherSuite 2nd impl, ADR-4 landed`

---

## Task 2: Zeroize + Keyring.destroy（TDD）

- [ ] **Step 1: 失败测试**

```java
package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZeroizeTest {
    @Test void wipesAllArrays() {
        byte[] a = {1, 2, 3}, b = {4, 5};
        Zeroize.wipe(a, b);
        assertArrayEquals(new byte[3], a);
        assertArrayEquals(new byte[2], b);
        Zeroize.wipe((byte[]) null);   // null 安全
    }
    @Test void keyringDestroyWipesAndBlocks() {
        Keyring kr = new Keyring();
        kr.add(1, new byte[]{9, 9, 9});
        kr.destroy();
        assertThrows(IllegalStateException.class, () -> kr.key(1));
        assertThrows(IllegalStateException.class, kr::activeVersion);
    }
}
```

- [ ] **Step 2: 实现 Zeroize；Keyring 加 destroy（wipe 所有版本 + 清 map + activeVersion=-1）；`DefaultSealManager.seal()` 改为 `if (keyring != null) keyring.destroy();` 后置 null；combine 出的 master 中间值已有清零，复查 submitUnsealKey 路径用 Zeroize 统一**

```java
package io.custos.engine.crypto;

import java.util.Arrays;

/** 密钥材料集中清零。 */
public final class Zeroize {
    private Zeroize() {}
    public static void wipe(byte[]... arrays) {
        if (arrays == null) return;
        for (byte[] a : arrays) if (a != null) Arrays.fill(a, (byte) 0);
    }
}
```

- [ ] **Step 3: 绿 + 全模块回归（OperatorService.seal() 行为不变——它调 sealManager.seal()）→ 提交** `feat(engine): Zeroize utility + destroyable Keyring (memory hardening)`

---

## Task 3: bench 标签用例 + RUNBOOK + AUDIT-PREP

- [ ] **Step 1: surefire 默认排除 bench**：engine pom surefire `configuration` 加 `<excludedGroups>bench</excludedGroups>`（与 api.version 并列）。

- [ ] **Step 2: 写 `BarrierBenchSmokeTest`（@Tag("bench")）**：循环 5 万次 seal/open 1KB，打印 ops/s 与 p99（System.nanoTime 简易直方图），断言仅"完成"——防悬崖不设硬阈值。

- [ ] **Step 3: `docs/bench/RUNBOOK.md`**：如何跑 bench 标签（`mvn -pl engine test -Dgroups=bench`）、JMH 工程化清单（barrier/JWT/PDP/query_db 四基准 + 环境要求 + 参考值表占位说明"首轮实测后填"）。

- [ ] **Step 4: `docs/audit/AUDIT-PREP.md`**：资产/信任边界（引 02 §3）、密钥生命周期表、依赖许可清单（引 00 §7）、已知缺口（mlock 未实现、admin TLS 为部署项、stdio 模式解封注入）、各模块测试数摘要、负路径清单。

- [ ] **Step 5: 提交** `docs(hardening): bench smoke + runbook + audit preparation package`

---

## Self-Review（对照加固 spec）

- 覆盖：GmSuite+keyLength 适配+Seal 集成(§2)→T1；Zeroize/Keyring.destroy(§3)→T2；bench/runbook(§4)→T3；AUDIT-PREP(§5)→T3。
- keyLength 兼容性：default 32 → IntlSuite 无改动、既有测试不破；仅 DefaultSealManager 两处长度取法变化（行为对 Intl 等价）。
- BC JCE 名称风险已声明（实测即核准）；无占位；bench 不进默认门禁。
