# Custos OBO 委托（On-Behalf-Of / STS）设计规格

> **类型**：生产架构路线图子项目 **P-OBO**（v0.2 第一项）设计。在已交付的 identity 模块之上新增 `authn`/`sts`。
> **校订**：2026-06-09 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec `2026-06-09-custos-production-architecture-spec.md` §7；详设 `docs/design/03-identity-design.md` §4/§7。
> 借 OAuth 2.1 Token Exchange (RFC 8693) / On-Behalf-Of；竞品（SPIRE/OpenBao/Vault/Infisical）均无此能力。

---

## 1. 目标与范围

让 Agent **代表用户**行动，签发的令牌权限取「用户授予 ∩ Agent 被允许 ∩ 本次请求」三者最小交集，并以 `act` 声明追溯"用户经哪个 Agent 做了什么"。

- **纳入**：`Principal`、`Authenticator` SPI + `JwtAuthenticator`、`AgentScopeResolver` SPI + 内存实现、`Sts` + `DefaultSts`（交集计算）、`JwtTokenService.issueOnBehalf`。纯 Java、可单测、SPI 可插拔。
- **非目标（留后续）**：外部 OIDC/JWKS 验签、SPIFFE/X.509-SVID（P-SPIFFE）、高危 JIT 人工审批（属 authz/v0.2 后续）、Nacos 身份注册表（接口留缝，内存实现先行）。准/拒仍由 PDP 决定，不在本增量。

---

## 2. 架构与数据流

```
Agent 持(用户JWT + 自身 AgentId)
        │  Sts.exchange(userToken, agent, requestedScope, ttl)
        ▼
DefaultSts
  ① Principal p = authenticator.authenticate(userToken)        // 验用户令牌→主体+scopes
  ② Set<String> agentAllowed = resolver.allowedScopes(agent)   // Agent 被允许集
  ③ effective = p.scopes() ∩ agentAllowed ∩ requestedScope     // 取最小
  ④ tokenService.issueOnBehalf(p.subject(), agent.toUri(), effective, audience, ttl)
        ▼
ScopedToken(JWT)：sub=用户, act=Agent SPIFFE-id, scope=交集, exp=短
        │  Agent 用该令牌调经纪层(PEP) → PDP 最终准/拒
        ▼
```

依赖方向遵循解耦铁律：`sts` 依赖 `authn`(Authenticator) + `AgentScopeResolver` + `TokenService` 接口，互不反依赖。

---

## 3. 组件与接口契约

`identity/src/main/java/io/custos/identity/`：

```java
// authn 主体
public record Principal(String subject, Set<String> scopes, Map<String, String> attributes) {}

// 可插拔认证 SPI（未来加 oidc/spiffe/mtls）
public interface Authenticator {
    /** 验证凭证（MVP 为 Custos 用户 JWT 字符串），返回主体；失败抛 TokenException。 */
    Principal authenticate(String credential);
}

// JWT 认证：用 TokenService.verify 验 Custos 签发的用户 JWT → Principal
public final class JwtAuthenticator implements Authenticator {
    public JwtAuthenticator(TokenService tokenService) { ... }
    // authenticate: TokenClaims c = tokenService.verify(jwt); return new Principal(c.subject(), c.scopes(), Map.of());
}

// Agent 被允许 scope 来源 SPI（未来接 Nacos 注册表/策略）
public interface AgentScopeResolver {
    Set<String> allowedScopes(AgentId agent);
}

public final class InMemoryAgentScopeResolver implements AgentScopeResolver {
    public void grant(AgentId agent, Set<String> scopes);   // 登记
    // allowedScopes: 返回登记集，未登记→空集（→交集空→最小权限）
}

// STS：OBO 交集令牌签发
public interface Sts {
    ScopedToken exchange(String userToken, AgentId agent, Set<String> requestedScope, Duration ttl);
}

public final class DefaultSts implements Sts {
    public DefaultSts(Authenticator authenticator, AgentScopeResolver resolver, TokenService tokenService, String audience) { ... }
    // exchange: 见 §2 四步
}
```

`JwtTokenService` 扩展（不破坏现有 `issue`）：
```java
/** OBO 委托签发：sub=userSubject、act=actorAgent、scope=给定交集。 */
ScopedToken issueOnBehalf(String userSubject, String actorAgent, Set<String> scopes, String audience, Duration ttl);
```
> 现有 `issue(AgentId, scopes, aud, ttl)` 把 `act` 硬编码 "broker"；`issueOnBehalf` 显式设 sub/act。两者并存。`verify` 已返回 `TokenClaims(subject, actor, scopes, audience, expiresAt)`，无需改。

---

## 4. 交集语义

- **有效 scope = `Principal.scopes` ∩ `agentAllowed` ∩ `requestedScope`**，`Set<String>` 精确交集（retainAll）。
- 空交集 → 签发 **空 scope** 委托令牌；PDP 端必然默认拒（符合最小权限，不抛异常，便于审计留痕）。
- `act` = `agent.toUri()`（SPIFFE 风格），审计可追溯委托方。
- `sub` = 用户主体（来自用户令牌的 subject）。

---

## 5. 错误处理

| 场景 | 处理 |
|---|---|
| 用户令牌签名错/过期/被吊销 | `Authenticator.authenticate` 透传 `TokenException` |
| Agent 未登记允许集 | `allowedScopes` 返回空集 → 交集空 → 空 scope 令牌（非异常） |
| requestedScope 为空 | 交集空 → 空 scope 令牌 |

---

## 6. 测试策略（TDD，纯单元）

- `Principal`/交集：用户∩Agent∩请求三者收窄正确；空交集产空 scope。
- `JwtAuthenticator`：有效用户 JWT→Principal(subject,scopes)；过期/换钥→TokenException。
- `DefaultSts.exchange`：签发令牌 `verify` 后 sub=用户、act=Agent、scope=交集；端到端 OBO 往返。
- `JwtTokenService.issueOnBehalf`：sub/act/scope 声明正确。

---

## 7. 非目标 / YAGNI

- 不做外部 OIDC/JWKS、SPIFFE/X.509、JIT 审批、Nacos 身份注册表实现（接口留缝）。
- 不改 PDP（交集令牌交由现有 authz 决策）。
- 不引入新模块；全部落在 identity 模块内 `authn`/`sts` 包。
