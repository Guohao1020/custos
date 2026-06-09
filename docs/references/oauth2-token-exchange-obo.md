# OAuth 2.0 Token Exchange（RFC 8693）与 On-Behalf-Of 委托

- **标题**：OAuth 2.0 Token Exchange（RFC 8693）/ On-Behalf-Of（OBO）委托
- **来源 URL**：
  - RFC 8693：https://datatracker.ietf.org/doc/html/rfc8693
  - OAuth 2.1（汇总草案）：https://oauth.net/2.1/
  - （概念参照）Microsoft identity platform OBO flow 文档
- **校订**：2026-06

## 核心要点（中文摘要）

- **Token Exchange（RFC 8693）**：定义一种用一个令牌换取另一个令牌的标准流程，端点参数含 `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`、`subject_token`（代表谁）、`actor_token`（谁在代理行动）、`scope`/`resource`/`audience`（收窄目标）。
- **delegation（委托）vs impersonation（假冒）**：委托保留"actor"信息（A 代表 B 行动，令牌含 `act` 声明），可追溯；假冒则丢失 actor。Agent 场景应用**委托**语义。
- **`act` 声明**：令牌中表达"实际操作方"，形成委托链，便于审计追溯"用户经哪个 Agent 做了什么"。
- **最小权限收窄**：通过 `scope`/`resource`/`audience` 把派生令牌的权限收到本次所需。

## 对 Custos 的影响

- Custos 身份层（`03` §4）的 **OBO 委托**直接采用 token-exchange：`subject_token=用户 OIDC 令牌`、`actor_token=Agent 身份` → 签发**作用域令牌**，其权限 = **用户 ∩ Agent ∩ 本次请求所需**，取最小。
- 令牌带 `act=Agent`，审计可追溯委托链（呼应 `02` 审计、`04` 决策）。
- 这是竞品（SPIRE/OpenBao/Vault/Infisical）都没有、Custos 必须**新造**的能力（`00` 对比表）。
