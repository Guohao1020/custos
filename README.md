# Custos · Agent 身份 · 密钥 · 权限统一引擎（设计文档库）

> **Custos**（拉丁语「守护者」）—— 为 **Nacos / Spring Cloud 生态**打造的、**自托管**的 **AI Agent 身份 · 密钥 · 权限**统一引擎。借鉴 Vault/OpenBao，但**密钥引擎完全自研**、**Nacos 作控制面**，支持**国密**、**Apache-2.0** 开源。
>
> **本仓库当前为「调研 + 设计」阶段产物**（不含生产代码）。代码实现是下一阶段的事。

---

## 0. 这是什么

本仓库收录 Custos 的**竞品调研笔记 + 设计文档 + 引用资料**。差异化护城河钉死在四点（详见 `docs/design/00-synthesis.md` §6）：

1. **Nacos-native**：注册 + 策略分发 + 秒级热推走 Nacos，国内 Java 企业"装上即用"。
2. **自托管 · 纯开源 · 自主可控**：Apache-2.0 + 国密 SM2/3/4 可切换 + 国产组件优先（Nacos/jCasbin）。
3. **身份 · 密钥 · 权限一体**：三层（身份/策略 PDP/经纪 PEP）+ 自研引擎内核，一个产品交付。
4. **策略热更新 = 秒级吊销**：Nacos 配置变更 → gRPC 秒级热推 → PDP/PEP 即时生效。

---

## 1. 文档导航

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

### 📚 引用资料中文摘要（`docs/references/`）
[MCP/SEP-835](docs/references/mcp-sep-835.md) · [OAuth2 Token-Exchange/OBO](docs/references/oauth2-token-exchange-obo.md) · [SPIFFE/SPIRE](docs/references/spiffe-spire.md) · [Vault/OpenBao Barrier·Seal·Lease](docs/references/vault-openbao-barrier-seal-lease.md) · [Cerbos 策略模型](docs/references/cerbos-policy-model.md) · [Casbin PERM 模型](docs/references/casbin-perm-model.md) · [Nacos MCP/配置热更新](docs/references/nacos-mcp-registry-config.md) · [国密 SM2/SM3/SM4](docs/references/gm-crypto-sm2-sm3-sm4.md)

### 📄 输入材料（`brief/`）
PRD（需求真相源）、任务书、交付灰度调研、Nacos 开发参考——`brief/` 目录。

---

## 2. 关键决策（已拍板）

| 决策 | 取向 |
|---|---|
| 引擎语言 | **Java 全栈**（生态一致；内存安全短板工程补齐）；SDK 提供 Spring Boot Starter |
| 解封方式 | **Shamir 默认 + KMS 接口预留** |
| 存储后端 | **MySQL 全密文**（首版）；Raft/JRaft HA（v0.3）|
| 国密策略 | **默认国际套件 + 国密可切换**（CipherSuite 抽象）|
| 授权落地 | **借 Cerbos 设计 + jCasbin 落地** + 自研 Nacos Watcher |
| 审计 | **哈希链/只追加防篡改**（差异化）|

---

## 3. 红线（贯穿全设计）

- 密钥引擎**完全自研**，不依赖/不抄 Vault(BSL)/OpenBao(MPL)/Infisical-EE(商业) 代码。
- **不自创密码学**：用 BouncyCastle/Tongsuo 实现标准/国密算法。
- **密钥绝不进 LLM 上下文**，也**绝不进 Nacos**。
- master key 不明文落盘；落盘前 Barrier 加密；审计防篡改；内存清零禁 swap；吊销可靠传播。
- Custos 自身 **Apache-2.0**；上生产前（v0.4）**外部安全审计**。

---

## 4. 目录结构

```
custos/
├── README.md                 # 本文件（文档总索引）
├── brief/                    # 输入材料：PRD / 任务书 / 调研
├── docs/
│   ├── research/             # 7 篇竞品学习笔记
│   ├── design/               # 00~08 设计文档
│   └── references/           # 8 篇引用资料中文摘要
├── research/                 # 竞品源码（本地克隆，.gitignore，不提交）
└── custos-design-docs.zip    # docs/ 打包交付物
```

> 说明：`engine/ identity/ authz/ broker/ nacos/ sdk/ cli/ examples/` 等代码目录在本阶段**仅作为 `08-repo-scaffold.md` 的设计描述**，尚未创建（下一阶段写代码时落地）。

---

## 5. 许可证

计划以 **Apache-2.0** 开源。本仓库设计文档与笔记为原创；竞品笔记仅总结公开架构与设计思想，未复制受版权/许可保护的源码。
