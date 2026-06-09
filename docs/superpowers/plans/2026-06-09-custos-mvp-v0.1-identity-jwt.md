# Custos MVP v0.1 — 身份层（JWT）Implementation Plan（计划 3/5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 TDD 实现 MVP 身份层——per-session JWT 令牌的签发与校验（ES256）、SPIFFE 风格 subject、scope/aud/exp 声明、短 TTL，以及基于黑名单的吊销与从外部 OIDC/JWT 派生主体。

**Architecture:** 独立 `identity` 模块，JWT 用经审计的 `io.jsonwebtoken:jjwt`（ES256 由库正确处理 JOSE 签名格式）。签名密钥对由调用方注入（完整接线时私钥来自 engine KeyManager，受 Barrier 保护，见计划 5）。黑名单为可热更新集合（计划 4 接 Nacos）。本计划纯单元可测，不依赖 engine/Nacos。

**Tech Stack:** Java 21 · io.jsonwebtoken:jjwt 0.12.5 · JUnit 5

> 前置：计划 1/5（仅复用其 Maven 父 POM）。对应 spec §3.7、详设 `docs/design/03-identity-design.md`。MVP **不含完整 OBO 委托链**（v0.2），但令牌预留 `act` 声明位。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `pom.xml` | 加 `identity` 到 `<modules>` |
| `identity/pom.xml` | identity 模块 POM（jjwt + JUnit）|
| `identity/src/main/java/io/custos/identity/AgentId.java` | SPIFFE 风格身份命名 |
| `identity/src/main/java/io/custos/identity/TokenClaims.java` | 解析后的令牌声明 |
| `identity/src/main/java/io/custos/identity/ScopedToken.java` | 签发结果 |
| `identity/src/main/java/io/custos/identity/TokenException.java` | 校验失败异常 |
| `identity/src/main/java/io/custos/identity/Blacklist.java` | 吊销黑名单接口 |
| `identity/src/main/java/io/custos/identity/InMemoryBlacklist.java` | 内存黑名单（计划 4 换 Nacos）|
| `identity/src/main/java/io/custos/identity/TokenService.java` | 签发/校验接口 |
| `identity/src/main/java/io/custos/identity/JwtTokenService.java` | jjwt 实现 |
| `identity/src/test/java/io/custos/identity/**` | 测试 |

---

## Task 1: identity 模块脚手架

**Files:**
- Modify: `pom.xml`
- Create: `identity/pom.xml`
- Create: `identity/src/main/java/io/custos/identity/AgentId.java`
- Test: `identity/src/test/java/io/custos/identity/AgentIdTest.java`

- [ ] **Step 1: 把 identity 加入父 POM `<modules>`**

修改 `pom.xml` 的 `<modules>` 为：
```xml
  <modules>
    <module>engine</module>
    <module>identity</module>
  </modules>
```

- [ ] **Step 2: 写 identity 模块 POM**

`identity/pom.xml`:
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
  <artifactId>custos-identity</artifactId>
  <dependencies>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>0.12.5</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>0.12.5</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>0.12.5</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: 写失败测试（AgentId 命名）**

`identity/src/test/java/io/custos/identity/AgentIdTest.java`:
```java
package io.custos.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentIdTest {

    @Test
    void rendersSpiffeStyleUri() {
        AgentId id = new AgentId("corp.example", "claude-prod", "sess-9f3a");
        assertEquals("custos://corp.example/agent/claude-prod/session/sess-9f3a", id.toUri());
    }

    @Test
    void parsesBackFromUri() {
        AgentId id = AgentId.parse("custos://corp.example/agent/claude-prod/session/sess-9f3a");
        assertEquals("corp.example", id.trustDomain());
        assertEquals("claude-prod", id.agent());
        assertEquals("sess-9f3a", id.session());
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -q -pl identity test -Dtest=AgentIdTest`
Expected: 编译失败（AgentId 未定义）。

- [ ] **Step 5: 实现 AgentId**

`identity/src/main/java/io/custos/identity/AgentId.java`:
```java
package io.custos.identity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SPIFFE 风格身份命名：custos://<trust-domain>/agent/<agent>/session/<session>。 */
public record AgentId(String trustDomain, String agent, String session) {

    private static final Pattern P =
            Pattern.compile("^custos://([^/]+)/agent/([^/]+)/session/([^/]+)$");

    public String toUri() {
        return "custos://" + trustDomain + "/agent/" + agent + "/session/" + session;
    }

    public static AgentId parse(String uri) {
        Matcher m = P.matcher(uri);
        if (!m.matches()) throw new IllegalArgumentException("invalid custos id: " + uri);
        return new AgentId(m.group(1), m.group(2), m.group(3));
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -pl identity test -Dtest=AgentIdTest`
Expected: PASS（2 个用例）。

- [ ] **Step 7: 提交**
```bash
git add pom.xml identity/pom.xml identity/src/main/java/io/custos/identity/AgentId.java identity/src/test/java/io/custos/identity/AgentIdTest.java
git commit -m "build(identity): module scaffold with SPIFFE-style AgentId"
```

---

## Task 2: TokenService 签发/校验（JWT ES256）

**Files:**
- Create: `identity/src/main/java/io/custos/identity/TokenClaims.java`
- Create: `identity/src/main/java/io/custos/identity/ScopedToken.java`
- Create: `identity/src/main/java/io/custos/identity/TokenException.java`
- Create: `identity/src/main/java/io/custos/identity/TokenService.java`
- Create: `identity/src/main/java/io/custos/identity/JwtTokenService.java`
- Test: `identity/src/test/java/io/custos/identity/JwtTokenServiceTest.java`

- [ ] **Step 1: 写失败测试（签发→校验拿到 claims；过期→失败；篡改/换钥→失败）**

`identity/src/test/java/io/custos/identity/JwtTokenServiceTest.java`:
```java
package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private final AgentId id = new AgentId("corp.example", "claude-prod", "s1");

    @Test
    void issueThenVerifyReturnsClaims() throws Exception {
        KeyPair kp = ec();
        TokenService svc = new JwtTokenService(kp, "custos", new InMemoryBlacklist());
        ScopedToken t = svc.issue(id, Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15));
        TokenClaims c = svc.verify(t.jwt());
        assertEquals(id.toUri(), c.subject());
        assertTrue(c.scopes().contains("tool:db/query_orders"));
        assertEquals("broker", c.audience());
    }

    @Test
    void expiredTokenFails() throws Exception {
        KeyPair kp = ec();
        TokenService svc = new JwtTokenService(kp, "custos", new InMemoryBlacklist());
        ScopedToken t = svc.issue(id, Set.of("s"), "broker", Duration.ofSeconds(-1));   // 已过期
        assertThrows(TokenException.class, () -> svc.verify(t.jwt()));
    }

    @Test
    void tokenSignedByOtherKeyFails() throws Exception {
        ScopedToken t = new JwtTokenService(ec(), "custos", new InMemoryBlacklist())
                .issue(id, Set.of("s"), "broker", Duration.ofMinutes(15));
        TokenService other = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        assertThrows(TokenException.class, () -> other.verify(t.jwt()));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -q -pl identity test -Dtest=JwtTokenServiceTest`
Expected: 编译失败（Token* 类未定义）。

- [ ] **Step 3: 写 TokenClaims / ScopedToken / TokenException / TokenService 接口 + Blacklist 接口**

`identity/src/main/java/io/custos/identity/TokenClaims.java`:
```java
package io.custos.identity;

import java.time.Instant;
import java.util.Set;

/** 校验通过后的令牌声明。 */
public record TokenClaims(String subject, String actor, Set<String> scopes,
                          String audience, Instant expiresAt) {}
```

`identity/src/main/java/io/custos/identity/ScopedToken.java`:
```java
package io.custos.identity;

import java.time.Instant;

public record ScopedToken(String jwt, Instant expiresAt) {}
```

`identity/src/main/java/io/custos/identity/TokenException.java`:
```java
package io.custos.identity;

/** 令牌无效：签名错/过期/被吊销/格式错。 */
public class TokenException extends RuntimeException {
    public TokenException(String message, Throwable cause) { super(message, cause); }
    public TokenException(String message) { super(message); }
}
```

`identity/src/main/java/io/custos/identity/TokenService.java`:
```java
package io.custos.identity;

import java.time.Duration;
import java.util.Set;

public interface TokenService {
    /** 签发 per-session 作用域令牌。actor 为 OBO 委托方（MVP 可为常量 "broker"，v0.2 接真实委托）。 */
    ScopedToken issue(AgentId subject, Set<String> scopes, String audience, Duration ttl);

    /** 校验签名/过期/黑名单，返回声明；失败抛 {@link TokenException}。 */
    TokenClaims verify(String jwt);
}
```

`identity/src/main/java/io/custos/identity/Blacklist.java`:
```java
package io.custos.identity;

/** 吊销黑名单：按 subject(=SPIFFE id) 判定是否已吊销。计划 4 由 Nacos 配置驱动热更新。 */
public interface Blacklist {
    boolean isRevoked(String subject);
}
```

- [ ] **Step 4: 实现 JwtTokenService（jjwt ES256）**

`identity/src/main/java/io/custos/identity/JwtTokenService.java`:
```java
package io.custos.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** JWT (ES256) 令牌服务：签名私钥来自注入的 KeyPair（生产由 engine KeyManager 提供，受 Barrier 保护）。 */
public final class JwtTokenService implements TokenService {

    private final KeyPair signingKey;
    private final String issuer;
    private final Blacklist blacklist;

    public JwtTokenService(KeyPair signingKey, String issuer, Blacklist blacklist) {
        this.signingKey = signingKey;
        this.issuer = issuer;
        this.blacklist = blacklist;
    }

    @Override
    public ScopedToken issue(AgentId subject, Set<String> scopes, String audience, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jwt = Jwts.builder()
                .issuer(issuer)
                .subject(subject.toUri())
                .audience().add(audience).and()
                .claim("scope", String.join(" ", scopes))
                .claim("act", "broker")
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey.getPrivate(), Jwts.SIG.ES256)
                .compact();
        return new ScopedToken(jwt, exp);
    }

    @Override
    public TokenClaims verify(String jwt) {
        try {
            Claims c = Jwts.parser()
                    .requireIssuer(issuer)
                    .verifyWith(signingKey.getPublic())
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            if (blacklist.isRevoked(c.getSubject())) {
                throw new TokenException("token subject is revoked: " + c.getSubject());
            }
            Set<String> scopes = new LinkedHashSet<>();
            String scope = c.get("scope", String.class);
            if (scope != null && !scope.isBlank()) {
                for (String s : scope.split(" ")) scopes.add(s);
            }
            String aud = (c.getAudience() == null || c.getAudience().isEmpty())
                    ? null : c.getAudience().iterator().next();
            return new TokenClaims(c.getSubject(), c.get("act", String.class), scopes, aud, c.getExpiration().toInstant());
        } catch (JwtException e) {
            throw new TokenException("invalid token", e);
        }
    }
}
```

- [ ] **Step 5: 写 InMemoryBlacklist（让测试可编译运行）**

`identity/src/main/java/io/custos/identity/InMemoryBlacklist.java`:
```java
package io.custos.identity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存黑名单（计划 4 替换为 Nacos 配置驱动）。 */
public final class InMemoryBlacklist implements Blacklist {

    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public void revoke(String subject) { revoked.add(subject); }
    public void clear() { revoked.clear(); }

    @Override
    public boolean isRevoked(String subject) { return revoked.contains(subject); }
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -q -pl identity test -Dtest=JwtTokenServiceTest`
Expected: PASS（3 个用例）。

- [ ] **Step 7: 提交**
```bash
git add identity/src/main/java/io/custos/identity identity/src/test/java/io/custos/identity/JwtTokenServiceTest.java
git commit -m "feat(identity): per-session JWT issue/verify with ES256"
```

---

## Task 3: 吊销（黑名单命中即拒）

**Files:**
- Test: `identity/src/test/java/io/custos/identity/RevocationTest.java`

> 实现已在 Task 2（`JwtTokenService.verify` 查 `Blacklist`、`InMemoryBlacklist`）。本任务补吊销路径的专门测试。

- [ ] **Step 1: 写失败测试**

`identity/src/test/java/io/custos/identity/RevocationTest.java`:
```java
package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RevocationTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    void revokedSubjectIsRejectedEvenWhenTokenStillValid() throws Exception {
        InMemoryBlacklist blacklist = new InMemoryBlacklist();
        TokenService svc = new JwtTokenService(ec(), "custos", blacklist);
        AgentId id = new AgentId("corp.example", "claude-prod", "s1");
        ScopedToken t = svc.issue(id, Set.of("s"), "broker", Duration.ofMinutes(15));

        assertNotNull(svc.verify(t.jwt()));          // 吊销前正常
        blacklist.revoke(id.toUri());                // 吊销
        assertThrows(TokenException.class, () -> svc.verify(t.jwt()));   // 未到 exp 也被拒
    }
}
```

- [ ] **Step 2: 运行测试，确认通过（实现已存在）**

Run: `mvn -q -pl identity test -Dtest=RevocationTest`
Expected: PASS。
> 若 FAIL，检查 Task 2 的 `verify` 是否在签名校验后查询 `blacklist.isRevoked`。

- [ ] **Step 3: 运行全部 identity 测试**

Run: `mvn -q -pl identity test`
Expected: 全部 PASS（AgentId + JwtTokenService + Revocation 共 6 用例）。

- [ ] **Step 4: 提交**
```bash
git add identity/src/test/java/io/custos/identity/RevocationTest.java
git commit -m "test(identity): revocation via blacklist rejects valid-but-revoked tokens"
```

---

## Self-Review（对照 spec §3.7、详设 03）

- **Spec 覆盖**：per-session JWT（§3.7 claims：sub=SPIFFE 风格、scope、aud、exp≤15min、act 预留）→ Task 2；SPIFFE 风格 subject → Task 1；吊销黑名单（§3.7 + 详设 03 §5）→ Task 3；签名用 ECDSA（详设 03 §2.2 / `02` CipherSuite）→ jjwt ES256。
- **类型一致性**：`AgentId.toUri/parse`、`TokenService.issue/verify`、`TokenClaims(subject,actor,scopes,audience,expiresAt)`、`ScopedToken(jwt,expiresAt)`、`Blacklist.isRevoked`、`InMemoryBlacklist.revoke` 跨任务一致。
- **占位扫描**：无 TODO/TBD；`act` 固定为 "broker" 是 MVP 明确简化（OBO 完整委托在 v0.2，spec §1 非目标已列）。
- **范围**：MVP 不做完整 OBO/多认证插件谱系；Authenticator（校验外部 IdP 令牌产出 Principal）在计划 5 接线时按需补薄封装。
- **可独立交付**：纯单元可测，无 engine/Nacos 依赖；产出可签发/校验/吊销的身份令牌服务。

> **下一计划**：4/5 策略层（jCasbin RBAC + Nacos Adapter/Watcher，秒级吊销端到端延迟测试）。
