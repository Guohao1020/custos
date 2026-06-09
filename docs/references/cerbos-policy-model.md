# Cerbos 策略模型（解耦 PDP）

- **标题**：Cerbos 授权策略模型（Resource/Principal Policies、Derived Roles、Scopes、可解释决策）
- **来源 URL**：https://docs.cerbos.dev/ · https://github.com/cerbos/cerbos
- **许可证**：Apache-2.0
- **校订**：2026-06

## 核心要点（中文摘要）

- **解耦 PDP**：Cerbos 是无状态的授权决策服务（gRPC/REST），应用在执行点发 `CheckResources` 请求，返回每个 action 的 `EFFECT_ALLOW/DENY`。策略外置（Git/Blob/DB）+ 热加载，改策略不动应用。
- **三类策略**：
  - **Resource Policy**：针对资源类型，为每个 action 列 roles/derivedRoles + condition。
  - **Principal Policy**：针对具体主体的特例覆盖。
  - **Derived Roles（派生角色）**：在 parentRoles 上叠加**上下文条件**派生动态角色（如 `R.attr.owner == P.id`），是 ABAC 的优雅表达。
- **条件用 CEL**（Common Expression Language）：`R.attr.*`/`P.attr.*`/`request.*` 入条件，支持按属性/上下文/风险判定。
- **可解释决策**：内部 tracer 可回答"为什么 allow/deny、命中哪条规则"。
- **Scopes（作用域）**：策略分层作用域（如 `acme.hr.uk`），继承与覆盖，适合多租户。
- **附加能力**：Schema 校验、AuthZEN 标准对齐、Decision Log、Query Planner（把权限编译成数据层过滤）。

## 对 Custos 的影响

- 权限层（`04`）**借其设计**：PDP/PEP 解耦、Derived Roles + CEL 的结构化 ABAC、**可解释决策（命中规则+原因）**、Scopes 对齐 Nacos namespace。
- 出于自主可控 + Java 同栈 + Nacos 原生：Custos **借 Cerbos 的"形"、用 jCasbin 的"魂"落地**（`00`/`04`）。
