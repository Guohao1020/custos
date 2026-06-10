---
id: SPEC-POSITIONING
type: spec
title: "项目定位 · ROADMAP · 对外文档体系"
status: reviewing
date: 2026-06-11
desc: "把项目现状+缺口固化为版本化 ROADMAP（真相源），并据此重写 README、新建 GitHub Pages 营销页"
---

# 项目定位 · ROADMAP · 对外文档体系

## 0 · 背景与动机

Custos 内核完成度高（M01–M14、`mvn -B clean verify` 全绿、全容器栈 AC1–AC8 真跑通），
但**产品对外叙事严重落后于代码**：

- README 仍是「设计文档库」骨架，落到 GitHub 的人看不出这是个能跑的产品。
- 没有 ROADMAP，缺口散落在脑子里和 `docs/audit/AUDIT-PREP.md`，没有版本化的真相源。
- 没有营销页，无法对外讲定位。
- **「Nacos 原生」是第一护城河，但代码里 Nacos 只干一件事**（存一个 policy 配置 + gRPC 热推），口号大于实证。
- 系统**缺后台管理**：审计链能 verify 却不能浏览；运行态（seal/租约/动态账号/决策速率）零监控。

本 spec 不写运行时功能代码，只产出三份**文档/页面**，并把上面缺口规划成版本化里程碑。
Admin Console、多引擎暴露等是 ROADMAP 中的**待建项**，由各自后续 spec→plan 落地，**不在本次范围**。

## 1 · 唯一真相源原则

定位 + ROADMAP 是真相源；README（面向开发者）与营销页（面向评估者）是它的两次渲染。
先定 ROADMAP，再渲染另两者，避免返工。

## 2 · 定位与四大护城河（对外口径）

一句话定位：**Nacos 原生、自托管、面向 AI Agent 的「身份·密钥·权限」统一引擎。**

| 护城河 | 实证（已落地，可演示） |
|---|---|
| Nacos 原生 | 策略走 Nacos 配置 + gRPC 秒级热推，实测吊销生效 ~275ms |
| 自研引擎 + 国密 | 引擎 100% 自研（不抄 Vault/OpenBao 代码）；CipherSuite 一键切国际/国密套件 |
| Secretless | Agent 永不持有 DB 凭证；现场签发 `v_ro_*` 即用即焚，凭证不出返回值/日志/LLM 上下文 |
| 防篡改审计 | 每次决策落哈希链，改一行即断链并定位 seq |

**诚实红线（贯穿三份产物）**：营销页/README 只讲已落地的真本事，愿景能力一律显式标 `roadmap`，
绝不把未完成写成已完成。承接项目「生产姿态而非 demo」原则。

## 3 · Nacos 的角色：现状 → 目标

「Nacos 原生」要从口号变实证，Nacos 的职责必须从「一个配置存储」升为「统一控制 + 发现平面」：

| 维度 | 现状 | 目标（落在哪个里程碑） |
|---|---|---|
| 策略控制面 | ✅ 一个 `custos-policy` 配置热推 | 扩到 mount 表 / 风险阈值 / 审批路由都 config-driven 热推（v0.6+） |
| MCP 工具注册中心 | ❌ 未用 | custos 把 `query_db` 等工具注册进 Nacos 3.2 AI 管理中心，Agent 经 Nacos 发现工具（v0.5） |
| 服务注册发现 | ❌ host 不注册 | 多 host 注册到 Nacos + 健康检查 + 互相发现（v0.6 起，支撑 v0.7 集群） |
| namespace = 租户 | 设计了 domain，未演示 | Nacos namespace 真隔离多租户策略/配置（v0.6） |
| 集群协调 | 自建 JRaft（M11，未装车） | **决策：优先靠 Nacos**（服务发现/配置/leader 提示）；自建 Raft 只保留 seal/storage 复制这种 Nacos 不适合的场景（v0.7） |

## 4 · ROADMAP 里程碑

| 版本 | 主题 | 关键内容 | 状态 |
|---|---|---|---|
| v0.1–v0.4 | 内核 | M01–M14：引擎/身份/权限/经纪/OBO/ABAC/AK·SK/KV/SPIFFE/SDK/HA 零件/国密/内存加固 | ✅ 已交付 |
| **v0.5** | Agent + Nacos AI 中心 | ① AK·SK/KV/PG 经 MCP 多工具暴露（不再只有 query_db）② custos MCP server 注册进 Nacos 3.2 AI 管理中心 ③ REQUIRE_APPROVAL 审批闭环 ④ 真 Claude/Codex 端到端实证 + 进 CI ⑤ Python SDK | 🔜 下一阶段 |
| **v0.6** | 后台管理 + 可观测 | ① **Admin Console（全自建独立）**：审计链浏览器（按 agent/时间/决策/资源过滤、断链定位）+ 实时监控（seal 态/活跃租约/动态账号/决策·拒绝速率/审批队列）+ 运维动作（解封/轮换）② custos 注册为 Nacos 服务 ③ metrics(Prometheus)/结构化日志/tracing ④ namespace 多租户演示 | 📋 规划 |
| **v0.7** | 集群 | ① 多 host JWT 签名钥共享（G3）② HA：协调优先靠 Nacos，自建 Raft 只留 seal/storage 复制 ③ 签名钥/CA 钥经 Barrier 托管 | 📋 规划 |
| **v1.0** | 生产加固 | ① G1–G6 逐项消除（TLS、最小权限角色替代 GRANT ALL、去固定 admin token、持久卷）② OpenAPI 契约 + 版本化 ③ 主密钥轮换 REST ④ 外部安全审计 | 📋 规划 |

里程碑映射缺口评估：v0.5 闭合「AI Agent 故事弱 + Nacos 实证薄」，v0.6 闭合「无后台/零可观测」，
v0.7 闭合「多 host 签名钥 + HA 未装车」，v1.0 闭合「G1–G6 + 生产门槛」。

### 4.1 · Admin Console 边界（v0.6，本次仅规划）

全自建独立 console，单一控制台读 custos REST + Nacos 两边数据，呈现统一面板：

- **custos 侧（自有数据）**：防篡改审计链浏览、seal 状态、活跃租约、动态账号、决策/拒绝速率、审批队列、运维动作。
- **Nacos 侧（读取呈现）**：当前生效策略、已注册 MCP 工具、已注册 custos 服务实例。

需要新增只读 REST（审计分页查询、租约列表、监控统计）——这些是 v0.6 的实现项，本 spec 只登记。

## 5 · README 重写结构（面向开发者）

从「设计文档库」骨架重写为产品门面：

1. Hero：一句话定位 + badges（license / build / Java 21 / Nacos 3.2）
2. **30 秒快速起**：`docker compose -f examples/docker-compose.yml up -d --build` → 解封 → 一次 query_db
3. 架构图：engine ← identity/authz ← broker ← app/cli/sdk + Nacos 控制面 + 一次 query_db 的 secretless 路径
4. **能力矩阵**：已交付 vs roadmap（诚实标注，数据取自 §4）
5. 模块状态：链到 docs-cockpit 看板
6. 接入：MCP（`examples/claude-mcp.json` / `mcp_smoke_client.py`）+ Java SDK starter
7. ROADMAP 链 + 贡献 + 许可证
8. 原「文档导航」降级为「深入文档」小节

## 6 · 营销页 + GitHub Pages（面向评估者）

### 6.1 · 发布机制

GitHub Pages 从 `main` 分支 `/docs` 目录发布。当前 `docs/index.html` 是 docs-cockpit 看板，冲突。
**方案**：营销页占 `docs/index.html`（Pages 首页）；docs-cockpit 输出改到 `docs/cockpit.html`
（改 `docs-cockpit.yaml` 的 `project.output`），营销页底部链到看板。

### 6.2 · 页面分区（纯静态单文件、零依赖、与 cockpit 视觉一致）

Hero + slogan → 痛点（Agent 要密钥，但你不能把密钥交给它）→ 四大护城河（§2）→
工作原理（一次 query_db 的 secretless 流程图）→ 对标 Vault/OpenBao 表 →
**shipped vs roadmap 诚实带**（数据取自 §4）→ CTA（`docker compose up` / GitHub）。

## 7 · 交付物清单

1. `docs/ROADMAP.md` —— §2–§4 固化（真相源）
2. `README.md` —— 按 §5 重写
3. `docs/index.html` —— §6 营销页；`docs/cockpit.html` —— 看板迁移；`docs-cockpit.yaml` output 改写
4. （可选）`CONTRIBUTING.md` —— 若 README §7 引用则补最小版

## 8 · 验收标准

- ROADMAP 含完整里程碑表 + Nacos 现状→目标表 + 两项架构决策记录；与 AUDIT-PREP 的 G1–G6 对得上。
- README 首屏即「能跑的产品」，30 秒快速起命令可复制即用；能力矩阵 shipped/roadmap 标注与 ROADMAP 一致。
- 营销页 `file://` 直开正常；GitHub Pages 发布后首页是营销页、看板在 `cockpit.html` 可达；`docs-cockpit render` 仍正常产出到新路径。
- 三份产物无「把 roadmap 写成已完成」的越线表述。

## 9 · 不做（YAGNI）

- 不在本次实现 Admin Console、多引擎暴露、Python SDK、审批闭环——它们是 ROADMAP 待建项，各自后续 spec→plan。
- 营销页不引第三方框架/构建链，纯静态单文件。
- 不做英文版文档（承接仓库中文 prose 约定；i18n 留待有海外需求时）。
