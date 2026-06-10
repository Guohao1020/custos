# Custos SPIFFE + X.509-SVID（M12）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** identity 模块加 X.509-SVID 载体：SpiffeId、自建 CA 签发 SVID（URI SAN）、验证提取身份、SpiffeAuthenticator 接入 Authenticator SPI。

**Architecture:** BouncyCastle bcpkix 构建证书（不自写 X.509）；`X509SvidIssuer` 内建自签 ECDSA P-256 根 CA，`issueSvid` 产短时证书（URI SAN=spiffe uri）；`X509SvidVerifier` 验链/有效期/提取 SAN；`SpiffeAuthenticator` PEM→Principal，复用既有 OBO/STS 链路。

**Tech Stack:** Java 21 · bcprov-jdk18on 1.78.1 + bcpkix-jdk18on 1.78.1 · JUnit 5（纯单元）

> 前置：M07（Authenticator/Principal/TokenException 已存在）。对应 spec `docs/superpowers/specs/2026-06-10-custos-spiffe-design.md`。
> BC 的 JcaX509v3CertificateBuilder/JcaContentSignerBuilder/GeneralName API 长期稳定；实现首步用编译器即核准，若有出入按编译错误就地修正（小风险，无需独立核准 gate）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `identity/pom.xml` | 加 bcprov/bcpkix 1.78.1 |
| `identity/src/main/java/io/custos/identity/SpiffeId.java` | spiffe URI 解析/渲染 |
| `identity/src/main/java/io/custos/identity/Svid.java` | (certificate, keyPair) |
| `identity/src/main/java/io/custos/identity/X509SvidIssuer.java` | 自建 CA + 签发 |
| `identity/src/main/java/io/custos/identity/X509SvidVerifier.java` | 验链 + 提取 SpiffeId |
| `identity/src/main/java/io/custos/identity/SpiffeAuthenticator.java` | PEM → Principal |
| `identity/src/test/java/io/custos/identity/SpiffeIdTest.java` 等 3 个测试 | TDD |

---

## Task 1: SpiffeId（TDD）

- [ ] **Step 1: 失败测试**

```java
package io.custos.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpiffeIdTest {
    @Test void rendersAndParses() {
        SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");
        assertEquals("spiffe://corp.example/agent/claude-prod", id.toUri());
        assertEquals(id, SpiffeId.parse("spiffe://corp.example/agent/claude-prod"));
    }
    @Test void rejectsNonSpiffe() {
        assertThrows(IllegalArgumentException.class, () -> SpiffeId.parse("https://x/y"));
        assertThrows(IllegalArgumentException.class, () -> SpiffeId.parse("spiffe://no-path"));
    }
}
```

- [ ] **Step 2: 实现**

```java
package io.custos.identity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SPIFFE ID：spiffe://<trust-domain>/<path>。 */
public record SpiffeId(String trustDomain, String path) {
    private static final Pattern P = Pattern.compile("^spiffe://([^/]+)/(.+)$");
    public String toUri() { return "spiffe://" + trustDomain + "/" + path; }
    public static SpiffeId parse(String uri) {
        Matcher m = P.matcher(uri);
        if (!m.matches()) throw new IllegalArgumentException("invalid spiffe id: " + uri);
        return new SpiffeId(m.group(1), m.group(2));
    }
}
```

- [ ] **Step 3: 绿 → 提交** `feat(identity): SpiffeId parse/render`

---

## Task 2: X509SvidIssuer + X509SvidVerifier（TDD）

- [ ] **Step 1: identity/pom.xml 加 BC 依赖**

```xml
    <dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk18on</artifactId><version>1.78.1</version></dependency>
    <dependency><groupId>org.bouncycastle</groupId><artifactId>bcpkix-jdk18on</artifactId><version>1.78.1</version></dependency>
```

- [ ] **Step 2: 失败测试**

```java
package io.custos.identity;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class X509SvidTest {
    private final X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
    private final X509SvidVerifier verifier = new X509SvidVerifier();
    private final SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");

    @Test void issueThenVerifyExtractsSameId() {
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        assertEquals(id, verifier.verify(svid.certificate(), issuer.caCertificate()));
    }
    @Test void expiredSvidRejected() {
        Svid svid = issuer.issueSvid(id, Duration.ofSeconds(-60));
        assertThrows(TokenException.class, () -> verifier.verify(svid.certificate(), issuer.caCertificate()));
    }
    @Test void foreignCaRejected() {
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        X509SvidIssuer other = new X509SvidIssuer("corp.example");
        assertThrows(TokenException.class, () -> verifier.verify(svid.certificate(), other.caCertificate()));
    }
    @Test void trustDomainMismatchAtIssueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> issuer.issueSvid(new SpiffeId("evil.example", "x"), Duration.ofMinutes(5)));
    }
}
```

- [ ] **Step 3: 实现 Issuer/Verifier（BC）**

```java
package io.custos.identity;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** 自建根 CA（ECDSA P-256 自签）并签发携 SPIFFE URI SAN 的短时 SVID。私钥仅内存。 */
public final class X509SvidIssuer {
    private final String trustDomain;
    private final KeyPair caKey;
    private final X509Certificate caCert;
    private final AtomicLong serial = new AtomicLong(1);

    public X509SvidIssuer(String trustDomain) {
        try {
            this.trustDomain = trustDomain;
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            this.caKey = g.generateKeyPair();
            X500Name subject = new X500Name("CN=custos-ca," + "O=" + trustDomain);
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(serial.getAndIncrement()),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(365))),
                    subject, caKey.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            this.caCert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
        } catch (Exception e) { throw new IllegalStateException("build CA failed", e); }
    }

    public X509Certificate caCertificate() { return caCert; }

    public Svid issueSvid(SpiffeId id, Duration ttl) {
        if (!trustDomain.equals(id.trustDomain()))
            throw new IllegalArgumentException("trust domain mismatch: " + id.trustDomain());
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    new X500Name("CN=custos-ca,O=" + trustDomain), BigInteger.valueOf(serial.getAndIncrement()),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(ttl)),
                    new X500Name("CN=" + id.path().replace('/', '-')), kp.getPublic());
            b.addExtension(Extension.subjectAlternativeName, true,
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, id.toUri())));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
            return new Svid(cert, kp);
        } catch (Exception e) { throw new IllegalStateException("issue svid failed", e); }
    }
}
```

```java
package io.custos.identity;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

/** 验证 SVID：CA 签名 + 有效期 + 提取 URI SAN → SpiffeId。失败抛 TokenException。 */
public final class X509SvidVerifier {
    public SpiffeId verify(X509Certificate cert, X509Certificate ca) {
        try {
            cert.verify(ca.getPublicKey());
            cert.checkValidity();
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if ((Integer) san.get(0) == 6) {            // 6 = URI
                        return SpiffeId.parse((String) san.get(1));
                    }
                }
            }
            throw new TokenException("no spiffe URI SAN in certificate");
        } catch (TokenException te) { throw te; }
        catch (Exception e) { throw new TokenException("invalid svid", e); }
    }
}
```
（`Svid` record：`public record Svid(java.security.cert.X509Certificate certificate, java.security.KeyPair keyPair) {}`）

- [ ] **Step 4: 绿（过期用例注意 `checkValidity` 抛 CertificateExpiredException → 包成 TokenException）→ 提交** `feat(identity): X.509-SVID issuer/verifier via BouncyCastle`

---

## Task 3: SpiffeAuthenticator（TDD）

- [ ] **Step 1: 失败测试**

```java
package io.custos.identity;

import org.junit.jupiter.api.Test;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class SpiffeAuthenticatorTest {
    private static String toPem(java.security.cert.X509Certificate c) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(c.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test void pemRoundTripsToPrincipal() throws Exception {
        X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
        SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        Authenticator auth = new SpiffeAuthenticator(new X509SvidVerifier(), issuer.caCertificate());
        Principal p = auth.authenticate(toPem(svid.certificate()));
        assertEquals(id.toUri(), p.subject());
        assertEquals("x509-svid", p.attributes().get("authn"));
    }
    @Test void garbagePemRejected() {
        X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
        Authenticator auth = new SpiffeAuthenticator(new X509SvidVerifier(), issuer.caCertificate());
        assertThrows(TokenException.class, () -> auth.authenticate("not-a-pem"));
    }
}
```

- [ ] **Step 2: 实现**

```java
package io.custos.identity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

/** PEM 证书 → 验 SVID → Principal(subject=spiffe uri)。scopes 留空（授权交 STS/PDP）。 */
public final class SpiffeAuthenticator implements Authenticator {
    private final X509SvidVerifier verifier;
    private final X509Certificate ca;

    public SpiffeAuthenticator(X509SvidVerifier verifier, X509Certificate ca) {
        this.verifier = verifier; this.ca = ca;
    }

    @Override
    public Principal authenticate(String pemCert) {
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pemCert.getBytes(StandardCharsets.UTF_8)));
            SpiffeId id = verifier.verify(cert, ca);
            return new Principal(id.toUri(), Set.of(), Map.of("authn", "x509-svid"));
        } catch (TokenException te) { throw te; }
        catch (Exception e) { throw new TokenException("invalid svid pem", e); }
    }
}
```

- [ ] **Step 3: 全 identity 回归（`mvn -pl identity test`）+ `mvn -B verify` → 提交** `feat(identity): SpiffeAuthenticator (PEM SVID -> Principal)`

---

## Self-Review（对照 SPIFFE spec）

- 覆盖：SpiffeId(§3)→T1；Issuer/Verifier + 错误处理(§3/§4)→T2（过期/异 CA/无 SAN/域不匹配全有用例）；Authenticator(§2/§3)→T3。类型一致（Authenticator/Principal/TokenException 复用 M07）。无占位。BC 1.78.1 为 Central 在档版本；JcaX509v3CertificateBuilder API 稳定，编译器即核准。
