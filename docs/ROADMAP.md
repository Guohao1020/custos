---
id: ROADMAP
type: roadmap
title: Custos ROADMAP
desc: "现状 + 版本化里程碑（真相源）"
---

# Custos ROADMAP

> 本文是 README 与营销页的**唯一真相源**：已交付能力（v0.1–v0.4）可断言，标 📋 / 🔜 的为**规划、未交付**。
> 任何对外文案，先以本文为准；愿景能力一律显式标 roadmap，绝不把未完成写成已完成。

## 产品主线：Nacos AI 栈的安全执行平面

Nacos 3.2 AI 管理中心做的是**发现**（谁有什么 MCP 工具 / Agent / Skill），它本身不含鉴权、密钥、审计。
这正是 Custos 的全部本事。二者是天然搭档：

> **Nacos AI 中心 = 注册发现平面；Custos = 安全执行平面（PEP + 密钥 + 审计）。**
> 关系类比 Nacos（注册）+ Sentinel（流控）—— 现在是 Nacos（AI 发现）+ Custos（AI 管控）。

「Nacos 原生」由此从一个集成点升为产品主线：经 Nacos 发现的每一次 Agent↔资源、Agent↔Agent 调用，
都先过 Custos 鉴权→签即用即焚凭证→落审计。具体杠杆点见下文「Nacos AI 中心杠杆点」。

## 四大护城河（对外口径）

一句话定位：**Nacos 原生、自托管、面向 AI Agent 的「身份·密钥·权限」统一引擎。**

| 护城河 | 实证（已落地，可演示） |
|---|---|
| Nacos 原生 | 策略走 Nacos 配置 + gRPC 秒级热推，实测吊销生效 ~275ms |
| 自研引擎 + 国密 | 引擎 100% 自研（不抄 Vault/OpenBao 代码）；CipherSuite 一键切国际/国密套件 |
| Secretless | Agent 永不持有 DB 凭证；现场签发 `v_ro_*` 即用即焚，凭证不出返回值/日志/LLM 上下文 |
| 防篡改审计 | 每次决策落哈希链，改一行即断链并定位 seq |

**诚实红线（贯穿三份产物）**：营销页/README 只讲已落地的真本事，愿景能力一律显式标 `roadmap`，
绝不把未完成写成已完成。承接项目「生产姿态而非 demo」原则。

## Nacos 的角色：现状 → 目标

「Nacos 原生」要从口号变实证，Nacos 的职责必须从「一个配置存储」升为「统一控制 + 发现平面」：

| 维度 | 现状 | 目标（落在哪个里程碑） |
|---|---|---|
| 策略控制面 | ✅ 一个 `custos-policy` 配置热推 | 扩到 mount 表 / 风险阈值 / 审批路由都 config-driven 热推（v0.6+） |
| MCP 工具注册中心 | ❌ 未用 | custos 把 `query_db` 等工具注册进 Nacos 3.2 AI 管理中心，Agent 经 Nacos 发现工具（v0.5） |
| 服务注册发现 | ❌ host 不注册 | 多 host 注册到 Nacos + 健康检查 + 互相发现（v0.6 起，支撑 v0.7 集群） |
| namespace = 租户 | 设计了 domain，未演示 | Nacos namespace 真隔离多租户策略/配置（v0.6） |
| **资源元数据** | ❌ 资源硬编码在 yaml | 资源非密元数据（连接串/方言/角色 SQL 模板/TTL）走 Nacos 配置，热重载 + 可发现（v0.5）；**高权限密钥不走 Nacos，Barrier 加密存 `custos_storage`** |
| 集群协调 | 自建 JRaft（M11，未装车） | **决策：优先靠 Nacos**（服务发现/配置/leader 提示）；自建 Raft 只保留 seal/storage 复制这种 Nacos 不适合的场景（v0.7） |

### Nacos AI 中心杠杆点（源码 + live API 实证，按里程碑归位）

研究 Nacos 3.2 AI 管理中心七大功能簇（MCP/A2A/Agent/Skill/Prompt/Pipeline/资源导入）后，留下高价值项：

| Nacos AI 能力 | Custos 怎么用 | 落点 |
|---|---|---|
| MCP 注册中心 CRUD | custos 注册为 MCP server；工具 `tool.annotations` 带权限/ABAC 标记；Agent 经 Nacos 发现 | v0.5 注册 / v0.6 富化元数据 |
| MCP endpoint 发现(DIRECT/REF) | custos-host 经 Nacos naming 注册端点；多 host 用 service-ref | v0.6 → v0.7 |
| **PEP/审计平面**（主线落地） | 经 Nacos 发现的每次工具调用先过 custos：鉴权→签短时凭证→审计 | v0.5/v0.6 |
| A2A Agent Card 注册 | Agent↔Agent 调用时 custos 做 PEP：解析对端 card→鉴权+审批+审计（复用 OBO/SPIFFE/ABAC） | v0.7 |
| Skill 注册带 required-permission | Skill 元数据声明所需密钥/权限，custos 激活时兑现 | v0.6/v0.7 |
| AI 资源批量导入 SPI | `custos-secrets-bridge` import source，管理员把治理资源 fleet 批量导入 Nacos 供发现 | v0.5（谨慎，避免资源清单外泄） |
| 版本/发布生命周期 | custos MCP server 版本化，版本信号 ABAC schema 破坏性变更 | v0.6+ |
| Higress 零代码 API→MCP + Spring AI Alibaba | custos REST 经 Higress 变 MCP 工具；插入阿里 AI 参考栈 | v0.6 生态 |

**剔除**：Prompt 管理（职责不交叠，不硬塞）；Pipeline（仅作 REQUIRE_APPROVAL 审批闭环的备选实现，不单列）；HiMarket 市场（v1.0+ 生态远景，备注）。

## 里程碑

| 版本 | 主题 | 关键内容 | 状态 |
|---|---|---|---|
| v0.1–v0.4 | 内核 | M01–M14：引擎/身份/权限/经纪/OBO/ABAC/AK·SK/KV/SPIFFE/SDK/HA 零件/国密/内存加固 | ✅ 已交付 |
| **v0.5** | 资源接入 + Agent + Nacos AI 中心 | ① **资源接入(首要)**：资源注册表 + 高权限密钥 Barrier 托管 + 角色/签发模型(hybrid 适配器+模板) + 多 DB 方言(见下文「资源接入模型」)② AK·SK/KV/PG 经 MCP 多工具暴露 ③ custos MCP server 注册进 Nacos 3.2 AI 管理中心 ④ REQUIRE_APPROVAL 审批闭环 ⑤ 真 Claude/Codex 端到端实证 + 进 CI ⑥ Python SDK | 🔜 下一阶段 |
| **v0.6** | 后台管理 + 可观测 + Nacos 深接 | ① **Admin Console（全自建独立）**：审计链浏览器（按 agent/时间/决策/资源过滤、断链定位）+ 实时监控（seal 态/活跃租约/动态账号/决策·拒绝速率/审批队列）+ 运维动作（解封/轮换）② custos 注册为 Nacos 服务 + MCP endpoint 经 naming 注册 ③ MCP 工具元数据富化（annotations 带 policy）+ 版本化 ④ Skill required-permission 兑现 ⑤ Higress 零代码 API→MCP 集成 ⑥ metrics(Prometheus)/结构化日志/tracing ⑦ namespace 多租户演示 | 📋 规划 |
| **v0.7** | 集群 + A2A | ① 多 host JWT 签名钥共享（G3）② HA：协调优先靠 Nacos，自建 Raft 只留 seal/storage 复制 ③ 签名钥/CA 钥经 Barrier 托管 ④ **A2A PEP**：custos 治理 Agent↔Agent 调用（经 Nacos A2A 注册解析对端 + 鉴权+审批+审计） | 📋 规划 |
| **v1.0** | 生产加固 | ① G1–G6 逐项消除（TLS、最小权限角色替代 GRANT ALL、去固定 admin token、持久卷）② OpenAPI 契约 + 版本化 ③ 主密钥轮换 REST ④ 外部安全审计 | 📋 规划 |

里程碑映射缺口评估：v0.5 闭合「资源接入硬编码 + AI Agent 故事弱 + Nacos 实证薄」，v0.6 闭合「无后台/零可观测」，
v0.7 闭合「多 host 签名钥 + HA 未装车」，v1.0 闭合「G1–G6 + 生产门槛」。

## 资源接入模型（v0.5 首要，规划）

Vault database-secrets-engine 模型，DB 优先，中间件/系统作为后续 secrets-engine 类型（SPI 已支持）：

- **资源 (Resource)**：受治理后端的注册条目 —— id、type/方言、连接串、一把 Barrier 加密的高权限管理凭证（root/admin）。
- **角色 (Role)**：如何对资源现场签发短时凭证 —— **hybrid**：常见库（MySQL/PostgreSQL/Oracle/SQLServer）出箱即用内置适配器；
  罕见库走 SQL 模板逃生口（管理员自填 `creation_statements`/`revocation_statements`）。附默认 TTL + 授权范围。
- **资源注册表 (Registry)**：运行时通过 REST/CLI 注册（v0.5）/ console GUI（v0.6）。非密元数据走 Nacos 配置热重载；
  **高权限密钥 Barrier 加密落 `custos_storage`，绝不明文、绝不进 Nacos，仅签发/撤销时内存解出、用后 Zeroize、可轮换**。

落地后消除现有硬编码：`target-jdbc-url` 单库 → 资源注册表多资源；明文 admin 凭证 → Barrier 托管；
MySQL 语法写死 → 适配器/模板。**这是「不要 demo 思维」原则在接入层的兑现，也是引擎核心价值（密钥托管）的正面体现。**

## 已知缺口

参见 [docs/audit/AUDIT-PREP.md](audit/AUDIT-PREP.md) 的 G1–G6。
