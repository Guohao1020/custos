# Custos OBO 委托（STS / token-exchange）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 identity 模块实现 OBO 委托：`Sts.exchange(userToken, agent, requestedScope, ttl)` 签发委托令牌（sub=用户、act=Agent、scope=用户授予∩Agent允许∩本次请求 的最小交集、短 TTL）。

**Architecture:** 纯 Java、自包含于 identity 模块。`JwtAuthenticator` 验 Custos 用户 JWT→`Principal`；`AgentScopeResolver` 给 Agent 允许集；`DefaultSts` 编排三步交集并经 `TokenService.issueOnBehalf` 签发。全部经接口解耦、SPI 可插拔（OIDC/SPIFFE/Nacos 注册表留缝）。

**Tech Stack:** Java 21 · io.jsonwebtoken:jjwt 0.12.5 · JUnit 5（沿用 identity 现有依赖，无新依赖）

> 前置：identity 模块（AgentId/TokenService/JwtTokenService/TokenClaims/ScopedToken 已存在）。对应 spec `docs/superpowers/specs/2026-06-09-custos-obo-design.md`、详设 `docs/design/03-identity-design.md` §4/§7。
> **精化（相对 spec）**：`issueOnBehalf` 定义在 `TokenService` 接口上（而非仅 JwtTokenService），使 `Sts` 依赖接口而非具体类。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `identity/src/main/java/io/custos/identity/Principal.java` | 认证产出的主体（subject + scopes + attributes）|
| `identity/src/main/java/io/custos/identity/Authenticator.java` | 认证 SPI |
| `identity/src/main/java/io/custos/identity/JwtAuthenticator.java` | 验 Custos 用户 JWT → Principal |
| `identity/src/main/java/io/custos/identity/TokenService.java` | 加 `issueOnBehalf`（接口）|
| `identity/src/main/java/io/custos/identity/JwtTokenService.java` | 实现 `issueOnBehalf` |
| `identity/src/main/java/io/custos/identity/AgentScopeResolver.java` | Agent 允许集 SPI |
| `identity/src/main/java/io/custos/identity/InMemoryAgentScopeResolver.java` | 内存实现 |
| `identity/src/main/java/io/custos/identity/Sts.java` | STS 接口 |
| `identity/src/main/java/io/custos/identity/DefaultSts.java` | 交集编排 |
| `identity/src/test/java/io/custos/identity/JwtAuthenticatorTest.java` | 认证测试 |
| `identity/src/test/java/io/custos/identity/DefaultStsTest.java` | OBO 端到端 + 交集测试 |

---

## Task 1: Principal + Authenticator + JwtAuthenticator + TokenService.issueOnBehalf

**Files:**
- Create: `identity/src/main/java/io/custos/identity/Principal.java`
- Create: `identity/src/main/java/io/custos/identity/Authenticator.java`
- Modify: `identity/src/main/java/io/custos/identity/TokenService.java`
- Modify: `identity/src/main/java/io/custos/identity/JwtTokenService.java`
- Create: `identity/src/main/java/io/custos/identity/JwtAuthenticator.java`
- Test: `identity/src/test/java/io/custos/identity/JwtAuthenticatorTest.java`

- [ ] **Step 1: 写 Principal + Authenticator**

`identity/src/main/java/io/custos/identity/Principal.java`:
```java
package io.custos.identity;

import java.util.Map;
import java.util.Set;

/** 认证产出的主体：subject（用户身份）、scopes（用户授予）、attributes（扩展属性，ABAC 用）。 */
public record Principal(String subject, Set<String> scopes, Map<String, String> attributes) {}
```

`identity/src/main/java/io/custos/identity/Authenticator.java`:
```java
package io.custos.identity;

/** 可插拔认证 SPI：验证凭证并返回主体。MVP 为 Custos 用户 JWT；未来加 oidc/spiffe/mtls。 */
public interface Authenticator {
    /** 验证凭证（MVP 为用户 JWT 字符串），返回主体；失败抛 {@link TokenException}。 */
    Principal authenticate(String credential);
}
```

- [ ] **Step 2: TokenService 接口加 issueOnBehalf**

`identity/src/main/java/io/custos/identity/TokenService.java` 在接口内追加：
```java
    /** OBO 委托签发：sub=userSubject、act=actorAgent、scope=给定（已算好的交集）。失败抛 {@link TokenException}。 */
    ScopedToken issueOnBehalf(String userSubject, String actorAgent, java.util.Set<String> scopes, String audience, java.time.Duration ttl);
```

- [ ] **Step 3: 写失败测试（JwtAuthenticator 往返 + 过期拒）**

`identity/src/test/java/io/custos/identity/JwtAuthenticatorTest.java`:
```java
package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthenticatorTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    void authenticatesCustosUserJwtToPrincipal() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        // 用 issueOnBehalf 铸一个用户令牌（sub=user, act=self），携带用户 scopes
        String userJwt = svc.issueOnBehalf("user:alice", "user:alice", Set.of("tool:db/query_orders", "tool:db/query_users"), "custos", Duration.ofMinutes(15)).jwt();

        Authenticator auth = new JwtAuthenticator(svc);
        Principal p = auth.authenticate(userJwt);
        assertEquals("user:alice", p.subject());
        assertTrue(p.scopes().contains("tool:db/query_orders"));
        assertTrue(p.scopes().contains("tool:db/query_users"));
    }

    @Test
    void expiredUserJwtRejected() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String expired = svc.issueOnBehalf("user:alice", "user:alice", Set.of("s"), "custos", Duration.ofSeconds(-1)).jwt();
        Authenticator auth = new JwtAuthenticator(svc);
        assertThrows(TokenException.class, () -> auth.authenticate(expired));
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -q -pl identity test -Dtest=JwtAuthenticatorTest`
Expected: 编译失败（issueOnBehalf/JwtAuthenticator 未定义）。

- [ ] **Step 5: JwtTokenService 实现 issueOnBehalf**

`identity/src/main/java/io/custos/identity/JwtTokenService.java` 在 `issue(...)` 方法后追加：
```java
    @Override
    public ScopedToken issueOnBehalf(String userSubject, String actorAgent, Set<String> scopes, String audience, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jwt = Jwts.builder()
                .issuer(issuer)
                .subject(userSubject)
                .audience().add(audience).and()
                .claim("scope", String.join(" ", scopes))
                .claim("act", actorAgent)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey.getPrivate(), Jwts.SIG.ES256)
                .compact();
        return new ScopedToken(jwt, exp);
    }
```
> `Instant`/`Date`/`Set`/`Duration` 已被现有 `issue` 引入，无需新 import。

- [ ] **Step 6: 写 JwtAuthenticator**

`identity/src/main/java/io/custos/identity/JwtAuthenticator.java`:
```java
package io.custos.identity;

import java.util.Map;

/** 用 TokenService.verify 验 Custos 签发的用户 JWT → Principal。 */
public final class JwtAuthenticator implements Authenticator {

    private final TokenService tokenService;

    public JwtAuthenticator(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Principal authenticate(String credential) {
        TokenClaims c = tokenService.verify(credential);   // 失败透传 TokenException
        return new Principal(c.subject(), c.scopes(), Map.of());
    }
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `mvn -q -pl identity test -Dtest=JwtAuthenticatorTest`
Expected: PASS（2 个用例）。

- [ ] **Step 8: 提交**
```bash
git add identity/src/main/java/io/custos/identity/Principal.java identity/src/main/java/io/custos/identity/Authenticator.java identity/src/main/java/io/custos/identity/TokenService.java identity/src/main/java/io/custos/identity/JwtTokenService.java identity/src/main/java/io/custos/identity/JwtAuthenticator.java identity/src/test/java/io/custos/identity/JwtAuthenticatorTest.java
git commit -m "feat(identity): Authenticator SPI + JwtAuthenticator + TokenService.issueOnBehalf"
```

---

## Task 2: AgentScopeResolver + Sts + DefaultSts（交集编排）

**Files:**
- Create: `identity/src/main/java/io/custos/identity/AgentScopeResolver.java`
- Create: `identity/src/main/java/io/custos/identity/InMemoryAgentScopeResolver.java`
- Create: `identity/src/main/java/io/custos/identity/Sts.java`
- Create: `identity/src/main/java/io/custos/identity/DefaultSts.java`
- Test: `identity/src/test/java/io/custos/identity/DefaultStsTest.java`

- [ ] **Step 1: 写 AgentScopeResolver + InMemoryAgentScopeResolver + Sts 接口**

`identity/src/main/java/io/custos/identity/AgentScopeResolver.java`:
```java
package io.custos.identity;

import java.util.Set;

/** Agent 被允许的 scope 集来源 SPI（未来接 Nacos 注册表/策略）。 */
public interface AgentScopeResolver {
    Set<String> allowedScopes(AgentId agent);
}
```

`identity/src/main/java/io/custos/identity/InMemoryAgentScopeResolver.java`:
```java
package io.custos.identity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 Agent 允许集（计划后续接 Nacos 注册表只换实现）。未登记 → 空集。 */
public final class InMemoryAgentScopeResolver implements AgentScopeResolver {

    private final Map<String, Set<String>> byAgent = new ConcurrentHashMap<>();

    public void grant(AgentId agent, Set<String> scopes) { byAgent.put(agent.toUri(), Set.copyOf(scopes)); }

    @Override
    public Set<String> allowedScopes(AgentId agent) { return byAgent.getOrDefault(agent.toUri(), Set.of()); }
}
```

`identity/src/main/java/io/custos/identity/Sts.java`:
```java
package io.custos.identity;

import java.time.Duration;
import java.util.Set;

/** 安全令牌服务：OBO token-exchange，签发用户∩Agent∩请求 最小交集的委托令牌。 */
public interface Sts {
    ScopedToken exchange(String userToken, AgentId agent, Set<String> requestedScope, Duration ttl);
}
```

- [ ] **Step 2: 写失败测试（交集收窄 + sub/act + 空交集）**

`identity/src/test/java/io/custos/identity/DefaultStsTest.java`:
```java
package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultStsTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private final AgentId agent = new AgentId("corp.example", "claude-prod", "s1");

    @Test
    void exchangeNarrowsToIntersectionWithSubAndAct() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        // 用户授予 {a,b,c}
        String userJwt = svc.issueOnBehalf("user:alice", "user:alice", Set.of("a", "b", "c"), "custos", Duration.ofMinutes(15)).jwt();
        InMemoryAgentScopeResolver resolver = new InMemoryAgentScopeResolver();
        resolver.grant(agent, Set.of("b", "c", "d"));   // Agent 允许 {b,c,d}

        Sts sts = new DefaultSts(new JwtAuthenticator(svc), resolver, svc, "broker");
        ScopedToken obo = sts.exchange(userJwt, agent, Set.of("c", "d"), Duration.ofMinutes(15));   // 请求 {c,d}

        TokenClaims c = svc.verify(obo.jwt());
        assertEquals("user:alice", c.subject(), "sub=用户");
        assertEquals(agent.toUri(), c.actor(), "act=Agent");
        assertEquals(Set.of("c"), c.scopes(), "交集 {a,b,c}∩{b,c,d}∩{c,d} = {c}");
    }

    @Test
    void emptyIntersectionYieldsEmptyScope() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String userJwt = svc.issueOnBehalf("user:bob", "user:bob", Set.of("a"), "custos", Duration.ofMinutes(15)).jwt();
        InMemoryAgentScopeResolver resolver = new InMemoryAgentScopeResolver();
        resolver.grant(agent, Set.of("z"));   // 无交集

        Sts sts = new DefaultSts(new JwtAuthenticator(svc), resolver, svc, "broker");
        ScopedToken obo = sts.exchange(userJwt, agent, Set.of("a"), Duration.ofMinutes(15));
        assertTrue(svc.verify(obo.jwt()).scopes().isEmpty(), "空交集 → 空 scope");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl identity test -Dtest=DefaultStsTest`
Expected: 编译失败（DefaultSts 未定义）。

- [ ] **Step 4: 写 DefaultSts**

`identity/src/main/java/io/custos/identity/DefaultSts.java`:
```java
package io.custos.identity;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** OBO 编排：验用户令牌 → 取 Agent 允许集 → 三者交集取最小 → 签发委托令牌(sub=用户,act=Agent)。 */
public final class DefaultSts implements Sts {

    private final Authenticator authenticator;
    private final AgentScopeResolver resolver;
    private final TokenService tokenService;
    private final String audience;

    public DefaultSts(Authenticator authenticator, AgentScopeResolver resolver, TokenService tokenService, String audience) {
        this.authenticator = authenticator;
        this.resolver = resolver;
        this.tokenService = tokenService;
        this.audience = audience;
    }

    @Override
    public ScopedToken exchange(String userToken, AgentId agent, Set<String> requestedScope, Duration ttl) {
        Principal user = authenticator.authenticate(userToken);                 // ① 验用户令牌（失败抛 TokenException）
        Set<String> agentAllowed = resolver.allowedScopes(agent);               // ② Agent 允许集
        Set<String> effective = new LinkedHashSet<>(user.scopes());             // ③ 用户∩Agent∩请求 取最小
        effective.retainAll(agentAllowed);
        effective.retainAll(requestedScope);
        return tokenService.issueOnBehalf(user.subject(), agent.toUri(), effective, audience, ttl);   // ④ sub=用户,act=Agent
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl identity test -Dtest=DefaultStsTest`
Expected: PASS（2 个用例：交集 {c}、空交集空 scope）。

- [ ] **Step 6: 运行全部 identity 测试，确认无回归**

Run: `mvn -q -pl identity test`
Expected: 全部 PASS（AgentId + JwtTokenService + Revocation + JwtAuthenticator + DefaultSts）。

- [ ] **Step 7: 提交**
```bash
git add identity/src/main/java/io/custos/identity/AgentScopeResolver.java identity/src/main/java/io/custos/identity/InMemoryAgentScopeResolver.java identity/src/main/java/io/custos/identity/Sts.java identity/src/main/java/io/custos/identity/DefaultSts.java identity/src/test/java/io/custos/identity/DefaultStsTest.java
git commit -m "feat(identity): OBO STS with user-intersect-agent-intersect-request scope"
```

---

## Self-Review（对照 OBO 设计 spec）

- **Spec 覆盖**：`Principal`/`Authenticator`/`JwtAuthenticator`→Task 1；`issueOnBehalf`→Task 1（接口+实现）；`AgentScopeResolver`/`InMemoryAgentScopeResolver`/`Sts`/`DefaultSts`→Task 2；交集语义(§4)→Task 2 `retainAll` 三次；空交集产空 scope→Task 2 测试断言。全覆盖。
- **类型一致性**：`Principal(subject,scopes,attributes)`、`Authenticator.authenticate(String)→Principal`、`TokenService.issueOnBehalf(String,String,Set,String,Duration)→ScopedToken`、`AgentScopeResolver.allowedScopes(AgentId)→Set`、`InMemoryAgentScopeResolver.grant`、`Sts.exchange(String,AgentId,Set,Duration)→ScopedToken`、`DefaultSts(Authenticator,AgentScopeResolver,TokenService,String)` 跨任务一致。`TokenClaims.actor()/subject()/scopes()`（计划 3 已有）被复用。
- **占位扫描**：无 TODO/TBD；代码步均含完整代码。
- **精化记录**：`issueOnBehalf` 置于 `TokenService` 接口（spec 原文写 JwtTokenService），使 `DefaultSts` 依赖接口——更解耦，已在 Task 1 Step 2 明确。
- **无新依赖**：仅用 identity 现有 jjwt/JUnit；纯单元、无 Docker。

> **下一子项目**：P-SPIFFE（依赖本 OBO）或 P-ABAC（独立）——各自 brainstorming→spec→plan。
