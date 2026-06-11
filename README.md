# Custos · Agent 身份·密钥·权限统一引擎

> **Nacos 原生、自托管、面向 AI Agent 的「身份·密钥·权限」统一引擎。**

![license](https://img.shields.io/badge/license-Apache--2.0-blue) ![java](https://img.shields.io/badge/Java-21-orange) ![nacos](https://img.shields.io/badge/Nacos-3.2-green)

Custos（拉丁语「守护者」）借鉴 Vault/OpenBao 的赛道，但**密钥引擎 100% 自研**、**Nacos 作控制面**、支持**国密**、**Apache-2.0** 开源。Java 21 · Maven 多模块。一键 `docker compose up` 起的就是产品完全体，目标企业内部生产级使用。

---

## 产品主线：Nacos AI 栈的安全执行平面

Nacos 3.2 AI 管理中心做的是**发现**（谁有什么 MCP 工具 / Agent / Skill），它本身不含鉴权、密钥、审计——这正是 Custos 的全部本事。二者是天然搭档：

> **Nacos AI 中心 = 注册发现平面；Custos = 安全执行平面（PEP + 密钥 + 审计）。**
> 关系类比 Nacos（注册）+ Sentinel（流控）—— 现在是 Nacos（AI 发现）+ Custos（AI 管控）。

经 Nacos 发现的每一次 Agent↔资源、Agent↔Agent 调用，都先过 Custos：鉴权 → 签即用即焚凭证 → 落审计。

---

## 30 秒快速起

```bash
docker compose -f examples/docker-compose.yml up -d --build   # MySQL + Nacos 3.2 + custos
# 解封 + 一次查询见 examples/demo.md 的 AC1 / AC4
```

起来的是全容器栈（MySQL + Nacos 3.2 + custos-host）。host **sealed 启动**，提交满阈值的 Shamir 分片（默认 5/3）解封后才装配引擎；随后一次 `query_db` 全程 secretless。完整 e2e 验收 runbook（AC1–AC8）见 [examples/demo.md](examples/demo.md)，开发约定见 [CLAUDE.md](CLAUDE.md)。

---

## 架构

模块依赖（自研引擎在最内核，向外是身份/策略/经纪，再向外是宿主/CLI/SDK）：

```
                         ┌─────────── Nacos 控制面 ───────────┐
                         │  custos-policy 配置 + gRPC 秒级热推  │
                         │  （实测策略翻转到拒绝 ~275ms）       │
                         └───────────────┬────────────────────┘
                                         │ reload
engine ← identity/authz ← broker ← app(custos-host) / cli / sdk
  │          │       │       │
  密钥引擎    身份     PDP     PEP
 (Barrier/   (per-   (RBAC+  (现场签发即用即焚凭证)
  Seal/审计) session) ABAC)
```

**一次 `query_db` 的 secretless 路径**（broker = PEP）：

```
token 校验 (JWT ES256)
   → PDP 决策 (Casbin RBAC+domain，ABAC 三态 ALLOW/DENY/REQUIRE_APPROVAL)
   → 现场签发 v_ro_*（CREATE USER + GRANT SELECT）
   → 只读执行（仅单条 SELECT/WITH）
   → finally DROP USER（即用即焚）
   → 每次决策 append 哈希链审计
```

凭证从不出现在返回值、日志或 LLM 上下文里。

---

## 能力矩阵

诚实标注，数据取自 [docs/ROADMAP.md](docs/ROADMAP.md)。✅ 已交付、可演示；🔜 下一阶段；📋 规划。

| 能力 | 状态 |
|---|---|
| 自研密钥引擎（Barrier / Seal-Unseal / 密钥层级 / 租约） | ✅ 已交付 |
| 身份层（per-session 身份 / JWT ES256 / OBO 委托） | ✅ 已交付 |
| 权限层（jCasbin RBAC+domain + ABAC 三态 + 可解释 PDP） | ✅ 已交付 |
| 经纪层（secretless PEP / 动态 DB 凭证 / 即用即焚） | ✅ 已交付 |
| AK·SK secrets engine（签发 / 撤销 / 轮换） | ✅ 已交付 |
| KV engine | ✅ 已交付 |
| SPIFFE/SPIRE 工作负载身份（X.509 SVID） | ✅ 已交付 |
| Java SDK（Spring Boot Starter） | ✅ 已交付 |
| 国密套件（SM4-GCM/SM3/SM2，CipherSuite 一键切换） | ✅ 已交付 |
| 防篡改哈希链审计 | ✅ 已交付 |
| 全容器栈 e2e（AC1–AC8 真跑通） | ✅ 已交付 |
| 资源接入（资源注册表 + 高权限密钥 Barrier 托管 + 多 DB 方言） | 🔜 v0.5 |
| 多引擎经 MCP 多工具暴露（AK·SK / KV / PG） | 🔜 v0.5 |
| custos MCP server 注册进 Nacos 3.2 AI 管理中心 | 🔜 v0.5 |
| 真 Claude/Codex 端到端实证 + 进 CI | 🔜 v0.5 |
| Python SDK | 🔜 v0.5 |
| Admin Console（审计链浏览器 + 实时监控 + 运维动作） | 📋 v0.6 |
| 可观测（Prometheus metrics / 结构化日志 / tracing） | 📋 v0.6 |
| HA 集群（协调优先靠 Nacos，Raft 留 seal/storage 复制） | 📋 v0.7 |
| A2A PEP（治理 Agent↔Agent 调用） | 📋 v0.7 |
| 生产加固（G1–G6 消除 / TLS / 外部安全审计） | 📋 v1.0 |

---

## 模块状态

完整模块看板（M01–M14 逐卡 + 关联 spec/plan/code）见 [docs/cockpit.html](docs/cockpit.html)。

---

## 接入

### MCP

custos-host 经 `custos.transport.mcp-stdio=true` 暴露 `query_db` 工具。客户端配置见 [examples/claude-mcp.json](examples/claude-mcp.json)，全链路冒烟：

```bash
python examples/mcp_smoke_client.py "SELECT 1"
```

进程 sealed 启动，工具调用未解封返回 SEALED 错误；冒烟脚本自动解封后再签令牌发起查询。

### Java SDK

引入 `custos-spring-boot-starter`，经 `custos.client.*` 配置目标 host，自动装配 `CustosClient`：

```xml
<dependency>
  <groupId>io.custos</groupId>
  <artifactId>custos-spring-boot-starter</artifactId>
</dependency>
```

---

## ROADMAP

已交付 vs 规划、产品主线、四大护城河详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 贡献

遵循 brainstorm → spec → plan → TDD → `mvn -B verify` 门禁的工作节奏（见 [CLAUDE.md](CLAUDE.md)）；绝不直接在 main 上开发，先开 `impl/<x>` 分支。

## 许可证

**Apache-2.0**。Custos 密钥引擎完全自研，不依赖/不抄 Vault(BSL)/OpenBao(MPL)/Infisical-EE 代码；竞品笔记仅总结公开架构与设计思想。

---

## 深入文档

> 以下为设计与调研参考资料，理解内核设计语言用。产品现状与规划以 [ROADMAP](docs/ROADMAP.md) 为准。

### 📐 设计文档（`docs/design/`）— 按顺序读

| # | 文档 | 内容 |
|---|---|---|
| 00 | [综合与决策](docs/design/00-synthesis.md) | 跨竞品对比、借鉴/放弃、自研边界、差异化钉死、**许可证合规表** |
| 01 | [总体架构](docs/design/01-architecture.md) | PDP/PEP 模型、三层+引擎内核、Nacos 控制面、数据流时序 |
| 02 | [引擎威胁模型与密码学设计](docs/design/02-engine-crypto-design.md) | **【重中之重】** Barrier/密钥层级/Seal-Unseal/内存安全/哈希链审计/国密套件 |
| 03 | [身份层设计](docs/design/03-identity-design.md) | per-session 身份、认证方法、**OBO 委托** |
| 04 | [策略层设计 PDP](docs/design/04-authz-design.md) | RBAC+ABAC、工具级 scope(SEP-835)、可解释、JIT 审批 |
| 05 | [Nacos 控制面集成](docs/design/05-nacos-integration.md) | 注册、**秒级吊销**、namespace、MCP/A2A |
| 06 | [经纪层设计 PEP](docs/design/06-secrets-broker.md) | 动态凭证、**secretless 经纪**、轮换 |
| 07 | [MVP 纵向线](docs/design/07-mvp-vertical-slice.md) | 模块清单 + WBS + 验收标准 |
| 08 | [仓库脚手架与选型](docs/design/08-repo-scaffold.md) | 目录结构、**引擎语言 Java vs Go 论证**、CI、依赖清单 |

### 🔍 竞品学习笔记（`docs/research/`）

| 笔记 | 学什么 |
|---|---|
| [openbao.md](docs/research/openbao.md) | 引擎内核：Barrier/Seal-Unseal/Lease/动态密钥/审计（MPL，不抄码）|
| [vault.md](docs/research/vault.md) | 赛道设计语言；BSL 只读参照 |
| [spire.md](docs/research/spire.md) | 工作负载身份：SVID/attestation/信任域（Apache，可深借）|
| [cerbos.md](docs/research/cerbos.md) | 解耦 PDP：策略模型/可解释决策 |
| [casbin.md](docs/research/casbin.md) | 国产授权库：PERM 模型（落地内核）|
| [nacos.md](docs/research/nacos.md) | 控制面：配置热更新/MCP Registry（护城河）|
| [infisical.md](docs/research/infisical.md) | 密钥平台 + "agents never see secret"（直接竞品）|
| [jimmer.md](docs/research/jimmer.md) | 持久化 ORM 选型：不可变实体 + 类型安全 DSL（Apache，国产活跃）|

### 📚 引用资料中文摘要（`docs/references/`）

[MCP/SEP-835](docs/references/mcp-sep-835.md) · [OAuth2 Token-Exchange/OBO](docs/references/oauth2-token-exchange-obo.md) · [SPIFFE/SPIRE](docs/references/spiffe-spire.md) · [Vault/OpenBao Barrier·Seal·Lease](docs/references/vault-openbao-barrier-seal-lease.md) · [Cerbos 策略模型](docs/references/cerbos-policy-model.md) · [Casbin PERM 模型](docs/references/casbin-perm-model.md) · [Nacos MCP/配置热更新](docs/references/nacos-mcp-registry-config.md) · [国密 SM2/SM3/SM4](docs/references/gm-crypto-sm2-sm3-sm4.md)
