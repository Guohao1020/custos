# 03 · 身份层设计（Identity & OBO 委托）

> **定位**：Agent 原生身份——**每 Agent / 每会话临时身份**、多认证方法、**OBO 委托（用户 ∩ Agent 取最小权限）**、在 Nacos 注册。设计灵感来源：SPIRE 的 attestation 与 SVID（Apache-2.0，可借鉴），委托借 OAuth 2.1 Token Exchange / OBO。
>
> 前提：`00-synthesis.md`、`01-architecture.md`。

---

## 1. 为什么 Agent 身份不同于"应用/人"

| 特性 | 传统应用/人 | AI Agent |
|---|---|---|
| 数量 | 有限、稳定 | **海量、临时** |
| 生命周期 | 长（部署级） | **短（会话/任务级）** |
| 代表谁 | 自己 | **代表用户行动（委托）** |
| 权限 | 角色固定 | **= 用户 ∩ Agent，取最小** |
| 凭证 | 长期 | **绝不持长期密钥/不进 LLM** |

→ 结论：借 SPIRE「不预置密钥的 attestation + 短时身份」，但**身份粒度下沉到"每会话"**，并新增「应用/人」体系里没有的 **OBO 委托**。

---

## 2. 身份命名与令牌

### 2.1 SPIFFE 风格命名（借鉴 SPIRE URI）
```
custos://<trust-domain>/agent/<agent-id>/session/<session-id>
例： custos://corp.example/agent/claude-prod/session/9f3a...
```
- 信任域 = 企业/环境；路径段标识 Agent 与会话；可在 Nacos namespace 下隔离。

### 2.2 双载体令牌（借鉴 SVID 的 X.509 + JWT）
| 载体 | 用途 | 内容 |
|---|---|---|
| **JWT-SVID 风格** | 默认；HTTP/MCP 调用携带 | claims：`sub`=SPIFFE-ID 风格、`act`（OBO 委托方）、`scope`（工具/动作）、`aud`、`exp`（短 TTL）、`risk` |
| **X.509-SVID 风格**（可选） | 需 mTLS 的服务间 | SPIFFE-ID 放 URI SAN |
- 签名算法：默认 **ECDSA P-256**，国密套件下 **SM2**（见 `02` CipherSuite）；签名私钥由引擎 KeyManager 管理（可托管 KMS）。
- **短 TTL**（默认会话级，如 ≤15min）+ 续签；会话结束即失效。

---

## 3. 认证方法（如何证明"Agent 是谁"，不预置长期密钥）

借 SPIRE attestation 思路，落为可插拔 `Authenticator`：

| 方法 | 机制 | 适用 | 优先级 |
|---|---|---|---|
| **JWT / OIDC** | 校验 IdP 签发的 OIDC token（Agent 运行平台/网关注入） | 通用、Claude/Codex 经网关 | P0 |
| **K8s ServiceAccount** | 校验投影 SA token（类似 SPIRE k8s_psat） | K8s 内工作负载 | P0 |
| **SPIFFE/SVID** | 直接信任既有 SPIRE 签发的 SVID | 已有 SPIFFE 体系 | 后续 |
| **mTLS 客户端证书** | 证书绑定身份 | 服务间 | 后续 |

- 认证成功 → 身份层签发 **per-session 作用域令牌**（不是长期密钥）。
- 与 Nacos：认证插件配置、信任的 IdP/issuer 列表存为 Nacos 配置，可热更新。

---

## 4. OBO 委托（用户 ∩ Agent 取最小）—— 本项目必须新造的能力

> 竞品（SPIRE/OpenBao/Vault/Infisical）**都没有**。借 **OAuth 2.1 Token Exchange（RFC 8693）/ On-Behalf-Of** 实现。

### 4.1 委托流程
```mermaid
sequenceDiagram
    autonumber
    participant U as 用户(SSO/IdP)
    participant A as Agent
    participant IDN as 身份层(STS)
    U->>A: 授权（用户 OIDC 令牌, 含用户权限/角色）
    A->>IDN: token-exchange(subject_token=用户令牌, actor_token=Agent 身份)
    Note over IDN: ① 校验用户令牌 + Agent 身份
② 计算 权限 = 用户权限 ∩ Agent 允许集
③ 取最小 + 按请求资源收窄 scope
    IDN-->>A: 作用域令牌(JWT)：sub=用户, act=Agent, scope=交集最小, exp=短
    A->>A: 用该令牌调经纪层(PEP)
```

### 4.2 交集语义
- **有效权限 = 用户授予 ∩ Agent 被允许 ∩ 本次请求所需**，三者取交并收到最小。
- 令牌含 `act`（actor=Agent）声明，审计可追溯"用户经哪个 Agent 做了什么"。
- 高危动作即使在交集内，仍触发 JIT + 人工审批（见 `04`）。

### 4.3 与权限层关系
- 身份层产出"主体上下文"（user + agent + 交集 scope + 风险）→ 交给策略层 PDP 做最终准/拒（`04`）。身份层负责"你是谁、代表谁、最多能要什么"，PDP 负责"此刻这件事准不准"。

---

## 5. 身份生命周期与吊销

| 阶段 | 机制 |
|---|---|
| 签发 | 认证 → per-session 令牌（短 TTL） |
| 续签 | 会话内续签，受 Agent 自治等级与风险约束 |
| **吊销** | ① 令牌短 TTL 自然失效；② 主动吊销：身份/会话黑名单写入 **Nacos 配置 → 秒级热推** 到 PDP/PEP；③ 用户侧 SSO 注销联动 |
| 注册 | Agent 身份、会话在 Nacos 注册与查询（ID4），namespace 隔离 |

> 吊销与 `05-nacos-integration`、`02`（撤销可靠传播）联动：身份失效 + 关联租约撤销。

---

## 6. 签名密钥管理（与引擎内核协作）
- 身份签名私钥（ECDSA/SM2）由引擎 **KeyManager** 持有：受 Barrier 保护、可托管 KMS（借 SPIRE KeyManager 思路）。
- 私钥仅签名瞬间在内存，用完清零（`02` §8 内存安全）。
- 支持签名密钥轮换 + 多版本验证（平滑过渡）。

---

## 7. 模块与接口（→ `08-repo-scaffold`）
```
identity/
  ├─ authn/         # 可插拔 Authenticator: oidc / k8s-sa / spiffe / mtls
  ├─ sts/           # token-exchange / OBO 交集计算
  ├─ token/         # JWT/X.509 SVID 签发与校验（调 CipherSuite）
  ├─ registry/      # 向 nacos 注册/查询身份
  └─ lifecycle/     # 续签 / 吊销 / 黑名单
```

| 接口 | 职责 |
|---|---|
| `Authenticator.authenticate(credential) → Principal` | 验证并返回主体 |
| `Sts.exchange(userToken, agentIdentity, requestedScope) → ScopedToken` | OBO 交集令牌 |
| `TokenService.issue/verify` | 签发/校验（调 `02` CipherSuite） |

---

## 8. 对 PRD 的覆盖与决策

| PRD | 覆盖 |
|---|---|
| ID1 per-session 身份 | §2 双载体短 TTL 令牌 |
| ID2 多认证方法 | §3 OIDC/K8s SA/SPIFFE/mTLS |
| ID3 OBO 委托 | §4 token-exchange 交集（新造）|
| ID4 Nacos 注册 | §5/§7 registry |

**待确认岔路口（已给推荐，影响 03/04）**：
- 会话令牌默认 TTL：推荐 **≤15min + 续签**（短窗口 + 可用性平衡）。
- 是否首版即支持 X.509-SVID：推荐**首版只 JWT，X.509 后续**（降复杂度）。
- 这两个非阻塞，已按推荐继续；如需调整告诉我。

> **下一篇**：`04-authz-design.md`（策略模型 / 工具级 scope / 可解释 / JIT 审批）。
