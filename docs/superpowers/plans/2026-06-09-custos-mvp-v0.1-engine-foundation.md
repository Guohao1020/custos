# Custos MVP v0.1 — 引擎密码学基座 Implementation Plan（计划 1/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭好 Maven 多模块工程，并以 TDD 实现 Custos 引擎的密码学基座——CipherSuite（AES-256-GCM/SHA-256/HMAC/ECDSA）、Barrier 加密封套、Shamir 分片、Seal/Unseal 与密钥层级——为后续存储/审计/租约/动态凭证打地基。

**Architecture:** 纯 Java、无外部服务依赖，全部可单元测试。Intl 密码套件用 JDK `javax.crypto`（AES-GCM/SHA-256/HMAC/ECDSA 均为 JDK 内置、经审计的标准算法实现）；Shamir 用经审计的 `com.codahale:shamir`（Apache-2.0）。**不自写任何密码学算法**。`CipherSuite` 抽象为后续国密 SM4/SM3/SM2 留切换点（本计划不实现国密）。

**Tech Stack:** Java 21 · Maven 多模块 · JUnit 5 · JDK javax.crypto · com.codahale:shamir

> **计划序列**（v0.1 全貌）：**1/5 引擎密码学基座（本计划）** → 2/5 引擎持久化（Storage/MySQL + 哈希链审计 + Lease + 动态 DB 凭证）→ 3/5 身份层（JWT 签发/校验）→ 4/5 策略层（jCasbin + Nacos Adapter/Watcher 秒级吊销）→ 5/5 经纪层 + MCP + docker-compose demo。每个计划独立可测。本计划对应详设 `docs/design/02-engine-crypto-design.md` 与 spec `docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md` §3.1–3.3。

---

## File Structure（本计划创建/修改的文件）

| 文件 | 职责 |
|---|---|
| `pom.xml` | 父 POM：聚合模块、依赖管理、Java 21 |
| `engine/pom.xml` | engine 模块 POM：shamir + JUnit |
| `.github/workflows/ci.yml` | CI：mvn verify |
| `engine/src/main/java/io/custos/engine/crypto/CipherSuite.java` | 密码套件接口（切换点）|
| `engine/src/main/java/io/custos/engine/crypto/IntegrityException.java` | 完整性校验失败异常 |
| `engine/src/main/java/io/custos/engine/crypto/IntlSuite.java` | 国际标准套件实现 |
| `engine/src/main/java/io/custos/engine/crypto/Keyring.java` | barrier 多版本密钥环 |
| `engine/src/main/java/io/custos/engine/barrier/Barrier.java` | Barrier 接口 |
| `engine/src/main/java/io/custos/engine/barrier/DefaultBarrier.java` | Barrier 封套实现（suite_id\|version\|body）|
| `engine/src/main/java/io/custos/engine/seal/ShamirSplitter.java` | Shamir 分片封装 |
| `engine/src/main/java/io/custos/engine/seal/SealStore.java` | 解封所需持久化抽象（本计划用内存实现）|
| `engine/src/main/java/io/custos/engine/seal/SealStatus.java` | 密封状态 |
| `engine/src/main/java/io/custos/engine/seal/SealedException.java` | 未解封时操作异常 |
| `engine/src/main/java/io/custos/engine/seal/SealManager.java` | 解封接口 |
| `engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java` | 解封实现（密钥层级）|
| `engine/src/test/java/io/custos/engine/**` | 对应测试 |

---

## Task 1: Maven 多模块脚手架 + CI + 冒烟测试

**Files:**
- Create: `pom.xml`
- Create: `engine/pom.xml`
- Create: `.github/workflows/ci.yml`
- Create: `engine/src/main/java/io/custos/engine/package-info.java`
- Test: `engine/src/test/java/io/custos/engine/SmokeTest.java`

- [ ] **Step 1: 写父 POM**

`pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.custos</groupId>
  <artifactId>custos-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>engine</module>
  </modules>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.2</junit.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.2.5</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: 写 engine 模块 POM**

`engine/pom.xml`:
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
  <artifactId>custos-engine</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.codahale</groupId>
      <artifactId>shamir</artifactId>
      <version>0.7.0</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: 写包说明与冒烟测试**

`engine/src/main/java/io/custos/engine/package-info.java`:
```java
/** Custos 自研密钥引擎内核：crypto / barrier / seal / storage / lease / audit。 */
package io.custos.engine;
```

`engine/src/test/java/io/custos/engine/SmokeTest.java`:
```java
package io.custos.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void buildAndTestPipelineWorks() {
        assertTrue(true, "脚手架就绪");
    }
}
```

- [ ] **Step 4: 写 CI**

`.github/workflows/ci.yml`:
```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B verify
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test`
Expected: BUILD SUCCESS，SmokeTest 1 passed。

- [ ] **Step 6: 提交**

```bash
git add pom.xml engine/pom.xml .github/workflows/ci.yml engine/src
git commit -m "build: maven multi-module scaffold with engine module and CI"
```

---

## Task 2: CipherSuite 接口 + IntlSuite 的 AES-256-GCM

**Files:**
- Create: `engine/src/main/java/io/custos/engine/crypto/CipherSuite.java`
- Create: `engine/src/main/java/io/custos/engine/crypto/IntegrityException.java`
- Create: `engine/src/main/java/io/custos/engine/crypto/IntlSuite.java`
- Test: `engine/src/test/java/io/custos/engine/crypto/IntlSuiteAeadTest.java`

- [ ] **Step 1: 写失败测试（加解密往返 + 篡改检测）**

`engine/src/test/java/io/custos/engine/crypto/IntlSuiteAeadTest.java`:
```java
package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteAeadTest {

    private final CipherSuite suite = new IntlSuite();

    private byte[] key() {
        byte[] k = new byte[32];           // AES-256
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        byte[] key = key();
        byte[] pt = "hello custos".getBytes(StandardCharsets.UTF_8);
        byte[] ct = suite.encrypt(key, pt, null);
        assertArrayEquals(pt, suite.decrypt(key, ct, null));
    }

    @Test
    void ciphertextDiffersFromPlaintextAndIsRandomized() {
        byte[] key = key();
        byte[] pt = "hello custos".getBytes(StandardCharsets.UTF_8);
        byte[] ct1 = suite.encrypt(key, pt, null);
        byte[] ct2 = suite.encrypt(key, pt, null);
        assertFalse(java.util.Arrays.equals(pt, ct1));
        assertFalse(java.util.Arrays.equals(ct1, ct2), "随机 nonce → 密文不同");
    }

    @Test
    void tamperedCiphertextThrowsIntegrityException() {
        byte[] key = key();
        byte[] ct = suite.encrypt(key, "data".getBytes(StandardCharsets.UTF_8), null);
        ct[ct.length - 1] ^= 0x01;          // 翻转一位
        assertThrows(IntegrityException.class, () -> suite.decrypt(key, ct, null));
    }

    @Test
    void wrongAadThrowsIntegrityException() {
        byte[] key = key();
        byte[] ct = suite.encrypt(key, "data".getBytes(StandardCharsets.UTF_8), "aad-1".getBytes());
        assertThrows(IntegrityException.class, () -> suite.decrypt(key, ct, "aad-2".getBytes()));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteAeadTest`
Expected: 编译失败（CipherSuite/IntlSuite/IntegrityException 未定义）。

- [ ] **Step 3: 写接口与异常**

`engine/src/main/java/io/custos/engine/crypto/CipherSuite.java`:
```java
package io.custos.engine.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 可切换密码套件：默认国际标准（{@link IntlSuite}），后续可加国密 GmSuite（SM4/SM3/SM2）。
 * encrypt 输出自带随机 nonce：[nonce(12) | ciphertext+tag]。所有算法由经审计的库实现，绝不自写。
 */
public interface CipherSuite {

    /** 套件标识，写入 Barrier 封套头：0x01=intl, 0x02=gm。 */
    byte suiteId();

    /** AEAD 加密（AES-256-GCM），返回 nonce||密文+tag。aad 可为 null。 */
    byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad);

    /** AEAD 解密并校验 tag；失败抛 {@link IntegrityException}。 */
    byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad);

    /** SHA-256。 */
    byte[] hash(byte[] data);

    /** HMAC-SHA-256。 */
    byte[] hmac(byte[] key, byte[] data);

    /** 生成签名密钥对（ECDSA P-256）。 */
    KeyPair genSignKey();

    byte[] sign(PrivateKey key, byte[] data);

    boolean verify(PublicKey key, byte[] data, byte[] sig);
}
```

`engine/src/main/java/io/custos/engine/crypto/IntegrityException.java`:
```java
package io.custos.engine.crypto;

/** 密文 tag 校验失败：数据被篡改或密钥/AAD 不符。读取方必须中止处理。 */
public class IntegrityException extends RuntimeException {
    public IntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: 实现 IntlSuite 的 AES-256-GCM**

`engine/src/main/java/io/custos/engine/crypto/IntlSuite.java`:
```java
package io.custos.engine.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/** 国际标准套件：AES-256-GCM / SHA-256 / HMAC-SHA-256 / ECDSA P-256，全部用 JDK 标准实现。 */
public class IntlSuite implements CipherSuite {

    private static final int NONCE_LEN = 12;     // 96-bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public byte suiteId() {
        return 0x01;
    }

    @Override
    public byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE_LEN];
            bb.get(nonce);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new IntegrityException("GCM tag mismatch", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    @Override
    public byte[] hash(byte[] data) {
        throw new UnsupportedOperationException("Task 3");
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data) {
        throw new UnsupportedOperationException("Task 3");
    }

    @Override
    public KeyPair genSignKey() {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public byte[] sign(PrivateKey key, byte[] data) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public boolean verify(PublicKey key, byte[] data, byte[] sig) {
        throw new UnsupportedOperationException("Task 4");
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteAeadTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 提交**

```bash
git add engine/src/main/java/io/custos/engine/crypto engine/src/test/java/io/custos/engine/crypto/IntlSuiteAeadTest.java
git commit -m "feat(engine): AES-256-GCM AEAD in CipherSuite/IntlSuite"
```

---

## Task 3: IntlSuite 的 SHA-256 与 HMAC-SHA-256

**Files:**
- Modify: `engine/src/main/java/io/custos/engine/crypto/IntlSuite.java`（替换 hash/hmac 桩）
- Test: `engine/src/test/java/io/custos/engine/crypto/IntlSuiteDigestTest.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/crypto/IntlSuiteDigestTest.java`:
```java
package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteDigestTest {

    private final CipherSuite suite = new IntlSuite();

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        byte[] h = suite.hash("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(h));
    }

    @Test
    void hmacIsStableAndKeyDependent() {
        byte[] k1 = "key1".getBytes(), k2 = "key2".getBytes(), data = "msg".getBytes();
        assertArrayEquals(suite.hmac(k1, data), suite.hmac(k1, data));
        assertFalse(java.util.Arrays.equals(suite.hmac(k1, data), suite.hmac(k2, data)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteDigestTest`
Expected: FAIL（`UnsupportedOperationException: Task 3`）。

- [ ] **Step 3: 实现 hash/hmac（替换桩）**

在 `IntlSuite.java` 中，把 `hash`/`hmac` 两个方法体替换为：
```java
    @Override
    public byte[] hash(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteDigestTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add engine/src/main/java/io/custos/engine/crypto/IntlSuite.java engine/src/test/java/io/custos/engine/crypto/IntlSuiteDigestTest.java
git commit -m "feat(engine): SHA-256 and HMAC-SHA-256 in IntlSuite"
```

---

## Task 4: IntlSuite 的 ECDSA P-256 签名/验签

**Files:**
- Modify: `engine/src/main/java/io/custos/engine/crypto/IntlSuite.java`（替换 genSignKey/sign/verify 桩）
- Test: `engine/src/test/java/io/custos/engine/crypto/IntlSuiteSignTest.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/crypto/IntlSuiteSignTest.java`:
```java
package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteSignTest {

    private final CipherSuite suite = new IntlSuite();

    @Test
    void signThenVerifySucceeds() {
        KeyPair kp = suite.genSignKey();
        byte[] data = "token-payload".getBytes();
        byte[] sig = suite.sign(kp.getPrivate(), data);
        assertTrue(suite.verify(kp.getPublic(), data, sig));
    }

    @Test
    void verifyFailsOnTamperedData() {
        KeyPair kp = suite.genSignKey();
        byte[] sig = suite.sign(kp.getPrivate(), "a".getBytes());
        assertFalse(suite.verify(kp.getPublic(), "b".getBytes(), sig));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteSignTest`
Expected: FAIL（`UnsupportedOperationException: Task 4`）。

- [ ] **Step 3: 实现 genSignKey/sign/verify（替换桩）**

在 `IntlSuite.java` 中替换三个桩方法：
```java
    @Override
    public KeyPair genSignKey() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] sign(PrivateKey key, byte[] data) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(PublicKey key, byte[] data, byte[] sig) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (java.security.SignatureException | java.security.InvalidKeyException e) {
            return false;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=IntlSuiteSignTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add engine/src/main/java/io/custos/engine/crypto/IntlSuite.java engine/src/test/java/io/custos/engine/crypto/IntlSuiteSignTest.java
git commit -m "feat(engine): ECDSA P-256 sign/verify in IntlSuite"
```

---

## Task 5: Keyring + Barrier 封套（suite_id | key_version | body）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/crypto/Keyring.java`
- Create: `engine/src/main/java/io/custos/engine/barrier/Barrier.java`
- Create: `engine/src/main/java/io/custos/engine/barrier/DefaultBarrier.java`
- Test: `engine/src/test/java/io/custos/engine/barrier/DefaultBarrierTest.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/barrier/DefaultBarrierTest.java`:
```java
package io.custos.engine.barrier;

import io.custos.engine.crypto.IntegrityException;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class DefaultBarrierTest {

    private Keyring keyringWith(int version) {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(version, k);
        return kr;
    }

    @Test
    void sealThenOpenRoundTrips() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(1));
        byte[] pt = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = barrier.seal(pt);
        assertArrayEquals(pt, barrier.open(sealed));
    }

    @Test
    void envelopeHeaderCarriesSuiteIdAndVersion() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(7));
        byte[] sealed = barrier.seal("x".getBytes());
        assertEquals(0x01, sealed[0], "suite_id");
        int version = ((sealed[1] & 0xff) << 24) | ((sealed[2] & 0xff) << 16)
                | ((sealed[3] & 0xff) << 8) | (sealed[4] & 0xff);
        assertEquals(7, version, "key_version");
    }

    @Test
    void tamperedEnvelopeFailsIntegrity() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(1));
        byte[] sealed = barrier.seal("x".getBytes());
        sealed[sealed.length - 1] ^= 0x01;
        assertThrows(IntegrityException.class, () -> barrier.open(sealed));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DefaultBarrierTest`
Expected: 编译失败（Keyring/Barrier/DefaultBarrier 未定义）。

- [ ] **Step 3: 实现 Keyring**

`engine/src/main/java/io/custos/engine/crypto/Keyring.java`:
```java
package io.custos.engine.crypto;

import java.util.HashMap;
import java.util.Map;

/** Barrier 多版本密钥环：支持轮换（新写用 active 版本，旧数据按 version 解密）。 */
public final class Keyring {

    private final Map<Integer, byte[]> keys = new HashMap<>();
    private int activeVersion = -1;

    /** 加入一个版本的密钥并设为 active。 */
    public void add(int version, byte[] key) {
        keys.put(version, key.clone());
        this.activeVersion = version;
    }

    public byte[] key(int version) {
        byte[] k = keys.get(version);
        if (k == null) throw new IllegalStateException("unknown key version: " + version);
        return k;
    }

    public int activeVersion() {
        if (activeVersion < 0) throw new IllegalStateException("keyring is empty");
        return activeVersion;
    }
}
```

- [ ] **Step 4: 实现 Barrier 接口与默认实现**

`engine/src/main/java/io/custos/engine/barrier/Barrier.java`:
```java
package io.custos.engine.barrier;

/** 加密屏障：所有数据落盘前 seal（加密），读取后 open（解密+校验）。存储后端只见密文。 */
public interface Barrier {
    byte[] seal(byte[] plaintext);
    byte[] open(byte[] ciphertext);
}
```

`engine/src/main/java/io/custos/engine/barrier/DefaultBarrier.java`:
```java
package io.custos.engine.barrier;

import io.custos.engine.crypto.CipherSuite;
import io.custos.engine.crypto.Keyring;

import java.nio.ByteBuffer;

/** 封套格式：[suite_id(1) | key_version(4, big-endian) | cipherSuite 输出(nonce+ct+tag)]。 */
public final class DefaultBarrier implements Barrier {

    private static final int HEADER_LEN = 1 + 4;

    private final CipherSuite suite;
    private final Keyring keyring;

    public DefaultBarrier(CipherSuite suite, Keyring keyring) {
        this.suite = suite;
        this.keyring = keyring;
    }

    @Override
    public byte[] seal(byte[] plaintext) {
        int version = keyring.activeVersion();
        byte[] body = suite.encrypt(keyring.key(version), plaintext, null);
        return ByteBuffer.allocate(HEADER_LEN + body.length)
                .put(suite.suiteId())
                .putInt(version)
                .put(body)
                .array();
    }

    @Override
    public byte[] open(byte[] ciphertext) {
        ByteBuffer bb = ByteBuffer.wrap(ciphertext);
        bb.get();                       // suite_id（MVP 仅一种套件，读过即可）
        int version = bb.getInt();
        byte[] body = new byte[bb.remaining()];
        bb.get(body);
        return suite.decrypt(keyring.key(version), body, null);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DefaultBarrierTest`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 提交**

```bash
git add engine/src/main/java/io/custos/engine/crypto/Keyring.java engine/src/main/java/io/custos/engine/barrier engine/src/test/java/io/custos/engine/barrier/DefaultBarrierTest.java
git commit -m "feat(engine): barrier envelope with keyring versioning"
```

---

## Task 6: Shamir 分片封装

**Files:**
- Create: `engine/src/main/java/io/custos/engine/seal/ShamirSplitter.java`
- Test: `engine/src/test/java/io/custos/engine/seal/ShamirSplitterTest.java`

- [ ] **Step 1: 写失败测试**

`engine/src/test/java/io/custos/engine/seal/ShamirSplitterTest.java`:
```java
package io.custos.engine.seal;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShamirSplitterTest {

    @Test
    void splitsIntoNAndRecombinesWithThreshold() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);

        ShamirSplitter splitter = new ShamirSplitter(5, 3);
        Map<Integer, byte[]> shares = splitter.split(secret);
        assertEquals(5, shares.size());

        // 任意 3 片可重建
        Map<Integer, byte[]> subset = new HashMap<>();
        shares.entrySet().stream().limit(3).forEach(e -> subset.put(e.getKey(), e.getValue()));
        assertArrayEquals(secret, splitter.combine(subset));
    }

    @Test
    void differentSubsetOfThresholdAlsoRecovers() {
        byte[] secret = "master-key-material-0123456789ab".getBytes();
        ShamirSplitter splitter = new ShamirSplitter(5, 3);
        Map<Integer, byte[]> shares = splitter.split(secret);

        Map<Integer, byte[]> subset = new HashMap<>();
        shares.entrySet().stream().skip(2).limit(3).forEach(e -> subset.put(e.getKey(), e.getValue()));
        assertArrayEquals(secret, splitter.combine(subset));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=ShamirSplitterTest`
Expected: 编译失败（ShamirSplitter 未定义）。

- [ ] **Step 3: 实现 ShamirSplitter（封装 com.codahale:shamir）**

`engine/src/main/java/io/custos/engine/seal/ShamirSplitter.java`:
```java
package io.custos.engine.seal;

import com.codahale.shamir.Scheme;

import java.security.SecureRandom;
import java.util.Map;

/** Shamir 秘密分享封装：用经审计的 com.codahale:shamir 实现，绝不自写算法。 */
public final class ShamirSplitter {

    private final int n;
    private final int k;

    public ShamirSplitter(int shares, int threshold) {
        if (threshold > shares || threshold < 1) {
            throw new IllegalArgumentException("invalid shares/threshold: " + shares + "/" + threshold);
        }
        this.n = shares;
        this.k = threshold;
    }

    /** 把 secret 切成 n 片（key=分片序号 1..n）。 */
    public Map<Integer, byte[]> split(byte[] secret) {
        return new Scheme(new SecureRandom(), n, k).split(secret);
    }

    /** 用 ≥k 片重建 secret。 */
    public byte[] combine(Map<Integer, byte[]> parts) {
        return new Scheme(new SecureRandom(), n, k).join(parts);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=ShamirSplitterTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 提交**

```bash
git add engine/src/main/java/io/custos/engine/seal/ShamirSplitter.java engine/src/test/java/io/custos/engine/seal/ShamirSplitterTest.java
git commit -m "feat(engine): Shamir secret sharing wrapper"
```

---

## Task 7: 密钥层级 + Seal/Unseal（init / unseal / seal / status）

> 密钥层级：unseal key（Shamir 分片）→ master key（加密 barrier key）→ barrier keyring。本任务用内存 `SealStore`（计划 2/5 换 MySQL 实现）。

**Files:**
- Create: `engine/src/main/java/io/custos/engine/seal/SealStore.java`
- Create: `engine/src/main/java/io/custos/engine/seal/SealStatus.java`
- Create: `engine/src/main/java/io/custos/engine/seal/SealedException.java`
- Create: `engine/src/main/java/io/custos/engine/seal/SealManager.java`
- Create: `engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java`
- Test: `engine/src/test/java/io/custos/engine/seal/DefaultSealManagerTest.java`

- [ ] **Step 1: 写失败测试（init→重启→提交分片→解封→Barrier 可用）**

`engine/src/test/java/io/custos/engine/seal/DefaultSealManagerTest.java`:
```java
package io.custos.engine.seal;

import io.custos.engine.barrier.Barrier;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSealManagerTest {

    /** 内存 SealStore，模拟跨重启的持久化。 */
    static final class InMemoryStore implements SealStore {
        final Map<String, byte[]> m = new HashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
    }

    @Test
    void initReturnsSharesAndStartsSealed() {
        InMemoryStore store = new InMemoryStore();
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        List<byte[]> shares = mgr.init(5, 3);
        assertEquals(5, shares.size());
        assertTrue(mgr.status().sealed());
    }

    @Test
    void unsealWithThresholdSharesUnlocksBarrier() {
        InMemoryStore store = new InMemoryStore();
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);

        // 模拟重启：新实例、同一 store、sealed
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        assertTrue(mgr.status().sealed());

        assertTrue(mgr.submitUnsealKey(shares.get(0)).sealed());   // 1/3
        assertTrue(mgr.submitUnsealKey(shares.get(1)).sealed());   // 2/3
        SealStatus after = mgr.submitUnsealKey(shares.get(2));     // 3/3 → unsealed
        assertFalse(after.sealed());

        // 解封后 barrier 可用
        Barrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        byte[] pt = "after-unseal".getBytes();
        assertArrayEquals(pt, barrier.open(barrier.seal(pt)));
    }

    @Test
    void operationsBeforeUnsealThrowSealed() {
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new InMemoryStore());
        // 未 init 也未 unseal
        assertThrows(SealedException.class, mgr::keyring);
    }

    @Test
    void sealClearsKeysAndRequiresReUnseal() {
        InMemoryStore store = new InMemoryStore();
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(1));
        mgr.submitUnsealKey(shares.get(2));
        assertFalse(mgr.status().sealed());

        mgr.seal();
        assertTrue(mgr.status().sealed());
        assertThrows(SealedException.class, mgr::keyring);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl engine test -Dtest=DefaultSealManagerTest`
Expected: 编译失败（Seal* 未定义）。

- [ ] **Step 3: 写 SealStore / SealStatus / SealedException / SealManager 接口**

`engine/src/main/java/io/custos/engine/seal/SealStore.java`:
```java
package io.custos.engine.seal;

import java.util.Optional;

/** 解封所需的持久化（被加密的 master、keyring 等）。计划 2/5 由 MySQL 实现，本计划用内存实现。 */
public interface SealStore {
    Optional<byte[]> get(String key);
    void put(String key, byte[] value);
}
```

`engine/src/main/java/io/custos/engine/seal/SealStatus.java`:
```java
package io.custos.engine.seal;

/** 密封状态。progress = 已提交的有效分片数；threshold = 解封所需阈值。 */
public record SealStatus(boolean sealed, int progress, int threshold) {}
```

`engine/src/main/java/io/custos/engine/seal/SealedException.java`:
```java
package io.custos.engine.seal;

/** 引擎处于 sealed 状态时执行需要密钥的操作即抛此异常。 */
public class SealedException extends RuntimeException {
    public SealedException() {
        super("engine is sealed; submit unseal keys first");
    }
}
```

`engine/src/main/java/io/custos/engine/seal/SealManager.java`:
```java
package io.custos.engine.seal;

import java.util.List;

public interface SealManager {

    /** 初始化：生成 master/barrier key，切分 unseal 分片（仅此一次返回），落盘加密产物。 */
    List<byte[]> init(int shares, int threshold);

    /** 提交一个 unseal 分片；达阈值则解封。 */
    SealStatus submitUnsealKey(byte[] share);

    /** 丢弃内存中的 master/barrier key，重新 sealed。 */
    void seal();

    SealStatus status();
}
```

- [ ] **Step 4: 实现 DefaultSealManager（密钥层级）**

`engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java`:
```java
package io.custos.engine.seal;

import io.custos.engine.crypto.CipherSuite;
import io.custos.engine.crypto.Keyring;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥层级：
 *   unseal key（= master key，经 Shamir 切片，分片不落盘）
 *     └─ 加密 → barrier key（落盘密文，key "wrapped_barrier"）
 * store 另存 shares/threshold 元数据（明文，非敏感）。
 */
public final class DefaultSealManager implements SealManager {

    private static final String K_WRAPPED_BARRIER = "wrapped_barrier";
    private static final String K_THRESHOLD = "threshold";
    private static final String K_SHARES = "shares";
    private static final int BARRIER_VERSION = 1;

    private final CipherSuite suite;
    private final SealStore store;
    private final SecureRandom random = new SecureRandom();

    // 解封态（内存）
    private Keyring keyring;                 // null = sealed
    private final Map<Integer, byte[]> collected = new HashMap<>();

    public DefaultSealManager(CipherSuite suite, SealStore store) {
        this.suite = suite;
        this.store = store;
    }

    @Override
    public List<byte[]> init(int shares, int threshold) {
        byte[] master = new byte[32];
        random.nextBytes(master);
        byte[] barrierKey = new byte[32];
        random.nextBytes(barrierKey);

        // master 加密 barrier key → 落盘
        store.put(K_WRAPPED_BARRIER, suite.encrypt(master, barrierKey, null));
        store.put(K_THRESHOLD, intBytes(threshold));
        store.put(K_SHARES, intBytes(shares));

        // master 切片（分片即 unseal key 的份额）
        Map<Integer, byte[]> parts = new ShamirSplitter(shares, threshold).split(master);

        // 进入解封态（init 后即 unsealed）
        this.keyring = new Keyring();
        this.keyring.add(BARRIER_VERSION, barrierKey);
        java.util.Arrays.fill(master, (byte) 0);   // 清零明文 master

        // 分片序号前缀编码进字节，提交时还原
        List<byte[]> out = new ArrayList<>();
        parts.forEach((idx, share) -> out.add(prefixIndex(idx, share)));
        return out;
    }

    @Override
    public SealStatus submitUnsealKey(byte[] share) {
        if (keyring != null) return status();   // 已解封
        int threshold = readInt(K_THRESHOLD);
        int shares = readInt(K_SHARES);

        int[] idxHolder = new int[1];
        byte[] raw = stripIndex(share, idxHolder);
        collected.put(idxHolder[0], raw);

        if (collected.size() >= threshold) {
            byte[] master = new ShamirSplitter(shares, threshold).combine(new HashMap<>(collected));
            byte[] barrierKey = suite.decrypt(master, store.get(K_WRAPPED_BARRIER)
                    .orElseThrow(() -> new IllegalStateException("not initialized")), null);
            this.keyring = new Keyring();
            this.keyring.add(BARRIER_VERSION, barrierKey);
            java.util.Arrays.fill(master, (byte) 0);
            collected.clear();
        }
        return status();
    }

    @Override
    public void seal() {
        this.keyring = null;
        collected.clear();
    }

    @Override
    public SealStatus status() {
        int threshold = store.get(K_THRESHOLD).map(DefaultSealManager::toInt).orElse(0);
        boolean sealed = (keyring == null);
        return new SealStatus(sealed, sealed ? collected.size() : threshold, threshold);
    }

    /** 解封后供 Barrier 使用的密钥环。 */
    public Keyring keyring() {
        if (keyring == null) throw new SealedException();
        return keyring;
    }

    // --- helpers ---
    private int readInt(String key) {
        return store.get(key).map(DefaultSealManager::toInt)
                .orElseThrow(() -> new IllegalStateException("not initialized: " + key));
    }

    private static byte[] intBytes(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static int toInt(byte[] b) {
        return ByteBuffer.wrap(b).getInt();
    }

    private static byte[] prefixIndex(int idx, byte[] share) {
        return ByteBuffer.allocate(4 + share.length).putInt(idx).put(share).array();
    }

    private static byte[] stripIndex(byte[] in, int[] idxOut) {
        ByteBuffer bb = ByteBuffer.wrap(in);
        idxOut[0] = bb.getInt();
        byte[] raw = new byte[bb.remaining()];
        bb.get(raw);
        return raw;
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl engine test -Dtest=DefaultSealManagerTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 运行全部引擎测试，确认无回归**

Run: `mvn -q -pl engine test`
Expected: 全部 PASS（CipherSuite/Barrier/Shamir/Seal 共 ~17 个用例）。

- [ ] **Step 7: 提交**

```bash
git add engine/src/main/java/io/custos/engine/seal engine/src/test/java/io/custos/engine/seal/DefaultSealManagerTest.java
git commit -m "feat(engine): seal/unseal with Shamir-protected key hierarchy"
```

---

## Self-Review（对照 spec §3.1–3.3）

- **Spec 覆盖**：CipherSuite（§3.1）→ Task 2–4；Barrier（§3.2 envelope `suite_id|key_version|nonce|ct+tag`）→ Task 5；Seal/Shamir + 密钥层级（§3.3 + 详设 02 §4/§6）→ Task 6–7。`engine/storage`（§3.4 MySQL）、`engine/lease`（§3.5）、`engine/audit`（§3.6）**不在本计划**，属计划 2/5（已在序列说明）。
- **类型一致性**：`CipherSuite` 接口签名在 Task 2 定义，Task 3/4 仅替换桩、签名不变；`Keyring.add/key/activeVersion`、`Barrier.seal/open`、`SealManager`/`DefaultSealManager.keyring()` 跨任务一致；`SealStore.get/put`、`SealStatus(sealed,progress,threshold)` 一致。
- **占位扫描**：无 TODO/TBD；Task 2 中 `IntlSuite` 的 hash/hmac/sign/verify 以 `UnsupportedOperationException("Task 3/4")` 显式占位，并在 Task 3/4 替换——这是 TDD 渐进实现，非计划占位。
- **可独立交付**：本计划产物（加解密/封套/解封）纯单元可测、无外部服务依赖，构成可复用的引擎密码学基座。

---

## 后续计划（序列）

- **2/5 引擎持久化**：`SealStore`/`Storage` 的 MySQL 实现（Testcontainers）、哈希链审计（append/verify）、LeaseManager、动态 DB 只读凭证（CREATE/DROP USER + TTL）。
- **3/5 身份层**：JWT 签发/校验（复用 IntlSuite 的 ECDSA）、claims、黑名单。
- **4/5 策略层**：jCasbin RBAC + Nacos Adapter/Watcher，秒级吊销端到端延迟测试。
- **5/5 经纪层 + demo**：MCP server、secretless 查询、docker-compose（Nacos+MySQL+Custos）、AC1–AC8 验收。
