# Casbin — 国产开源授权库（PERM 元模型，自主可控选型重点）

> **一句话定位**：Casbin 是国产、跨语言的**授权（access control）库**，以 **PERM 元模型**统一表达 ACL / RBAC / ABAC / RBAC-with-domains / BLP/Biba 等多种模型；**Apache-2.0**，有 Java（jCasbin）实现。它是 Custos 权限层"**国内可控授权内核**"的头号候选。
>
> 本笔记基于本地克隆 `research/casbin`（`enforcer.go`、`model/`、`rbac/`、`effector/`、`persist/`、`examples/*.conf`）。

---

## 1. 它解决什么问题 & 核心架构

把"授权逻辑"从业务代码里抽出来，用**两份可配置文件**描述：
- **Model（`model.conf`）**：定义"授权用什么形状的规则来判"（请求、策略、角色、效果、匹配器）。
- **Policy（`policy.csv` 或 DB）**：具体的策略行（谁能对什么做什么）。

```mermaid
flowchart LR
    REQ["Enforce(sub, obj, act)"] --> ENF[Enforcer]
    MODEL[Model: PERM 定义\nrequest/policy/role/effect/matcher] --> ENF
    ADP[(Adapter\nfile / MySQL / ...)] -->|加载/保存 policy| ENF
    WATCH[(Watcher\nNATS/Redis/etcd...)] -. 策略变更通知 .-> ENF
    ENF --> MATCH[Matcher 求值\n含 RBAC g() / ABAC 属性]
    MATCH --> EFF[Effector 合并效果]
    EFF --> RESULT{allow / deny}
```

**PERM = Policy, Effect, Request, Matchers**（+Role）。例（RBAC-with-domains，来自 `examples/rbac_with_domains_model.conf`）：

```ini
[request_definition]
r = sub, dom, obj, act
[policy_definition]
p = sub, dom, obj, act
[role_definition]
g = _, _, _                # 角色继承（带 domain/tenant）
[policy_effect]
e = some(where (p.eft == allow))
[matchers]
m = g(r.sub, p.sub, r.dom) && r.dom == p.dom && r.obj == p.obj && r.act == p.act
```

ABAC 例（`examples/abac_model.conf`）：matcher 直接读对象属性 `m = r.sub == r.obj.Owner`——**同一套引擎，靠 matcher 表达式在 RBAC 与 ABAC 间切换**。

---

## 2. 关键机制如何实现（含源码定位）

### 2.1 PERM 元模型与求值（`enforcer.go` 的 `Enforce()`）
- `request_definition (r)`：请求长什么样（`sub,obj,act` 或加 `dom`）。
- `policy_definition (p)`：策略行结构。
- `role_definition (g)`：角色继承关系，`g(a,b)` 表示 a 继承 b；`g` 可带 domain（三参）。
- `policy_effect (e)`：多条命中策略如何合并成最终效果（如 `some(where(p.eft==allow))` = 有一条 allow 即放行；也支持 deny-override 等）。
- `matchers (m)`：布尔表达式，决定一条请求是否匹配一条策略；可调 `g()`、读属性、用内置函数。

### 2.2 RBAC 与角色管理（`rbac/`、`rbac_api.go`）
- 角色管理器维护用户↔角色↔角色的继承图，支持**多层角色**与 **domain/tenant 隔离的 RBAC**（每租户独立角色体系）。
- `rbac_api.go` 提供 `AddRoleForUser`、`GetRolesForUser`、`GetImplicitPermissionsForUser` 等。

### 2.3 内置匹配函数（`examples/keymatch*/glob/ipmatch`）
- `keyMatch`/`keyMatch2`（RESTful 路径通配）、`globMatch`、`ipMatch`、`regexMatch` 等——**天然适合"工具/动作级 scope"的路径/通配匹配**（对 Custos A2 对齐 MCP SEP-835 有用）。

### 2.4 策略存储与热同步（`persist/`、Adapter、Watcher）
- **Adapter**：策略可存 file / MySQL / 各种 DB（`persist/` 抽象）。
- **Watcher**：多实例部署时，一个节点改了策略，经 NATS/Redis/etcd 等**通知其他实例重载**——`enforcer_distributed.go`、`enforcer_synced.go`、`enforcer_cached.go` 支持分布式/同步/缓存式 enforcer。
- **关键启示**：Casbin 已有"策略变更→多实例同步"的 Watcher 抽象——**Custos 可实现一个"Nacos Watcher"**：Nacos 配置变更（策略）→ 推送 → enforcer 重载 = **秒级权限变更与吊销**。这是把 Casbin 接入 Nacos 护城河的关键缝合点。

---

## 3. 在 AI Agent 场景下的不足 / 与 Nacos 生态的脱节

| 维度 | Casbin 的局限（站在 Custos 立场） |
|---|---|
| **是"库"不是"PDP 服务"** | Casbin 嵌进进程里用；不像 Cerbos 那样自带 gRPC/REST 的独立 PDP。Custos 若要 PDP/PEP 分离，需自己把它包成服务 |
| **可解释性弱** | 返回 allow/deny；不像 Cerbos 那样天然给"命中了哪条策略、为何"。Custos A4 要可解释决策需自行增强 |
| **无身份/密钥/委托** | 纯授权；不签身份、不发密钥、无 OBO、无 JIT 审批——只覆盖 Custos 权限层 |
| **无 MCP / 工具语义** | 不懂 MCP SEP-835；但其 keyMatch/glob 匹配可被改造来表达工具/动作 scope |
| **无 Nacos 原生集成** | Watcher 有 NATS/Redis/etcd 等实现，**没有现成 Nacos Watcher**——需 Custos 自研（但抽象现成，工作量小） |
| **风险/上下文判定靠 matcher 表达式** | ABAC 靠 matcher 写表达式，复杂策略可读性/治理性不如 Cerbos 的结构化 policy |

---

## 4. 可借鉴的设计 vs 要避免的坑

| ✅ 借鉴 | ⚠️ 要避免 / 改造 |
|---|---|
| **PERM 元模型**：用 request/policy/role/effect/matcher 统一表达 RBAC+ABAC——Custos A1 可直接采用 | 不要止步于"库内嵌"——Custos 需 **PDP 服务化**（PEP 分离），把 jCasbin 包成可独立评估的 PDP |
| **matcher 中的 keyMatch/glob** 表达路径/工具通配 → 承载**工具/动作级 scope（SEP-835）** | 可解释性要**额外增强**（返回命中策略与原因），否则达不到 PRD A4 |
| **Watcher 抽象** → 自研 **Nacos Watcher** 实现"配置热更新=秒级吊销" | 复杂 ABAC 全塞 matcher 字符串 → 治理性差；考虑结构化策略 + matcher 混合 |
| **domain/tenant RBAC** → 对齐 **Nacos namespace 多租户隔离** | — |
| **jCasbin（Java 实现）** → 与 Custos 引擎语言（倾向 Java）天然契合 | 跨语言一致性问题：选定 jCasbin 后锁版本与模型语义 |

---

## 5. 许可证与对 Custos 的约束

| 项 | 内容 |
|---|---|
| **许可证** | **Apache-2.0**（`research/casbin/LICENSE`）——与 Custos 同许可，**可放心依赖与借鉴**，甚至可作为**运行时依赖**（jCasbin）直接集成。 |
| **自主可控** | Casbin 为**国产开源**（PingCAP/社区背景），契合"国产组件优先、自主可控"诉求；Apache 基金会风格治理，对外依赖清晰。 |
| **集成方式建议** | 方案 A：把 **jCasbin 作为 Custos 权限层的求值内核**（RBAC+ABAC），外面包一层 Custos 的 PDP 服务 + 可解释增强 + Nacos Watcher；方案 B：借鉴 PERM 思想自研。**倾向 A**（自主可控 + 省自研 + Java 生态一致），在 `04-authz-design.md` 与 `08-repo-scaffold.md` 详述对比。 |

> **结论**：Casbin 是 Custos 权限层的**国产可控授权内核首选**——PERM 元模型可直接承载 RBAC+ABAC，Watcher 抽象可缝合 Nacos 实现秒级吊销，jCasbin 与 Java 引擎契合，Apache-2.0 可直接依赖。但它是"库"且可解释性弱：Custos 需把它**服务化为 PDP**、补**可解释决策**、写 **Nacos Watcher**、并用 keyMatch/glob 承载 **MCP 工具级 scope**。与 Cerbos 的取舍见 `cerbos.md` 与 `04-authz-design.md`。
