# Custos 实现任务书（交给 Claude Code 执行的提示词）

> 把本文件放进你的工作目录，连同 `custos-agent-secrets-PRD.md` 一起交给 Claude Code。本文件是给 Claude Code 的**总指令**；PRD 是**需求真相源**。

---

## 0. 你的角色与总目标

你是一名资深的安全基础设施 + 云原生架构师。你的任务是：**先深度调研同类开源竞品，再设计一个属于国内、自主可控的开源项目 `Custos`** —— 一个为 **Nacos / Spring Cloud 生态**打造的、自托管的 **AI Agent 身份 · 密钥 · 权限统一引擎**（"为 Agent 重做的 Vault"，但密钥引擎完全自研，Nacos 作注册/控制面）。

**本阶段只做「调研 + 设计」，不写生产代码。** 产出是一整套设计文档 + 竞品学习笔记 + 引用资料包。代码实现是下一阶段的事。

开始前，**先通读 `custos-agent-secrets-PRD.md`**，它定义了项目定位、三层架构（身份/策略 PDP/经纪 PEP）、自研引擎内核、MVP 纵向线、安全 NFR。本任务书与 PRD 冲突时，以 PRD 为准。

执行前请先输出一份**工作计划**（分阶段、产出清单、预计目录结构）给我确认，再开始动手。

---

## 1. 三阶段工作流

### 阶段一 · 竞品调研（clone + 深度学习）

把下列仓库 clone 到 `./research/<name>/`，逐个**精读源码与文档**，重点理解**架构与设计**，而非记忆代码。

| 名称 | 仓库 | 重点学什么 |
|------|------|-----------|
| OpenBao | `https://github.com/openbao/openbao` | 密钥引擎内核：Barrier 加密、Seal/Unseal、Storage 后端抽象、Lease 租约、动态 secrets engine、审计 |
| HashiCorp Vault | `https://github.com/hashicorp/vault` | 同上（设计参考）。⚠️ BSL 许可，**只读设计、严禁抄代码** |
| Infisical | `https://github.com/Infisical/infisical` | 找其中的 **Agent Vault / 凭证代理**部分：agent 不见密钥的 secretless 经纪做法 |
| SPIFFE / SPIRE | `https://github.com/spiffe/spire` | 非人类/工作负载身份：SVID 签发、信任域、attestation |
| Cerbos | `https://github.com/cerbos/cerbos` | 解耦的授权策略引擎（PDP）：策略模型、评估、可解释决策 |
| Casbin | `https://github.com/casbin/casbin` | 国产授权库：RBAC/ABAC 模型，作为权限层的国内可控选型参考 |
| Nacos | `https://github.com/alibaba/nacos` | 注册/配置中心 + MCP Registry：如何挂接为控制面、配置热更新机制 |

> 如某仓库结构与上表不符（如 Agent Vault 已独立成库），自行搜索定位，并在笔记中记录实际位置。

**每个竞品产出一份**`./docs/research/<name>.md`，包含：
- 它解决什么问题、核心架构（画 ASCII/mermaid 图）
- 关键机制如何实现（尤其引擎类：seal/unseal、加密层级、租约、撤销）
- 它在 Agent 场景下的不足 / 与 Nacos 生态的脱节
- **可借鉴的设计** vs **要避免的坑**
- 许可证与对我们的约束

### 阶段二 · 综合与决策

产出 `./docs/design/00-synthesis.md`：
- 跨竞品的设计对比表（引擎内核 / 身份 / 权限 / 委托 / 审计 / 部署）
- 提炼出 Custos 应**借鉴的设计模式**与**明确放弃的做法**
- 钉死差异化：**Nacos-native + 自托管 + 身份·密钥·权限一体 + 策略热更新秒级吊销**
- 明确「自研 vs 不重造」边界（密码学算法用审计库、身份可借鉴 SPIFFE 思路等）

### 阶段三 · 设计国内自研开源项目

产出以下设计文档（`./docs/design/`）：

1. `01-architecture.md` —— 总体架构：PDP/PEP 模型、三层 + 引擎内核、Nacos 控制面、数据流时序图。
2. `02-engine-crypto-design.md` —— **【最关键】威胁模型与密码学设计**：Barrier 加密、密钥层级（master→barrier）、Seal/Unseal（Shamir 分片 / KMS 自动解封）、存储加密、内存安全、防篡改审计（哈希链）。明确所用密码库与算法。
3. `03-identity-design.md` —— Agent 身份（per-session）、认证方法（JWT/OIDC/K8s SA/SPIFFE）、**OBO 委托**（用户 ∩ Agent 取最小）。
4. `04-authz-design.md` —— 策略模型（RBAC+ABAC/PBAC）、工具/动作级 scope（对齐 MCP SEP-835）、JIT + 人工审批、可解释决策。
5. `05-nacos-integration.md` —— 注册（Agent/资源/策略/实例）、配置热更新 = **秒级权限变更与吊销**、namespace 隔离、服务发现。
6. `06-secrets-broker.md` —— 动态 DB 凭证、**secretless 经纪**（MCP-native，密钥不进 LLM）、轮换。
7. `07-mvp-vertical-slice.md` —— 把 PRD 第 7 节的纵向线落成可实现的模块清单 + 任务拆解（WBS）+ 验收标准。
8. `08-repo-scaffold.md` —— 仓库目录结构（见下）、模块边界、技术选型、构建与 CI 草案。

---

## 2. 硬约束（不可违背）

1. **Nacos-native**：注册与策略分发以 Nacos 为控制面，这是项目护城河，不可替换为别的注册中心。
2. **密钥引擎完全自研**：不依赖 Vault/OpenBao 的内核或其代码，只借鉴设计思想。
3. **不自创密码学**：用经过审计的密码库（如 BouncyCastle、Tink、libsodium、Go crypto）实现**标准算法**，绝不自己写加密算法。
4. **密钥绝不进入 LLM 上下文**：经纪层只回结果，凭证带 TTL + 最小只读权限。
5. **安全红线（写进每份引擎设计）**：master key 不明文落盘（Shamir/KMS）；数据落盘前 Barrier 加密；审计防篡改（哈希链/只追加）；密钥内存用完清零、禁 swap；吊销/租约可靠传播。
6. **栈对齐**：面向 Java / Spring Cloud + K8s + Nacos 3.x。
7. **文档用中文写**（代码与标识符用英文）。

---

## 3. 强化「国内自主可控」的两个要求

1. **国密算法支持**：在密码学设计中，除 AES-256-GCM 外，**评估并支持国密 SM2/SM3/SM4**（用合规库如 BouncyCastle GM / 铜锁 Tongsuo），作为信创/自主可控卖点。给出可切换的算法套件设计。
2. **国产组件优先**：身份/注册用 Nacos（阿里）、权限模型参考 Casbin（国产），在选型文档中说明自主可控考量与对外依赖清单。

---

## 4. 引擎语言：先做决策

引擎内核用 **Java** 还是 **Go**，是个真问题：Java 与你们 Spring Cloud/Nacos 生态一致、团队熟悉；Go 与竞品（Vault/SPIRE）一致、内存与密钥控制更细。**请在 `08-repo-scaffold.md` 中对二者做对比并给出推荐与理由**，默认倾向 Java（生态一致、Nacos 原生），但若你论证 Go 更适合引擎内核可提出。SDK 侧无论如何提供 Java/Spring Boot Starter。

---

## 5. 仓库脚手架（目标结构，阶段三细化）

```
custos/
├── engine/        # 自研密钥引擎内核（barrier/seal/storage/lease/audit）
├── identity/      # Agent 身份与 OBO 委托
├── authz/         # 策略引擎 PDP（RBAC/ABAC，MCP scope）
├── broker/        # 经纪/执行 PEP（MCP-native，secretless）
├── nacos/         # Nacos 控制面集成（注册 + 热更新吊销）
├── sdk/           # Spring Boot Starter / 客户端
├── cli/           # custos CLI（解封、策略、审计）
├── examples/      # docker-compose / helm + MVP 纵向线 demo
└── docs/          # 设计文档 + 竞品笔记 + 引用资料
```

---

## 6. 交付物与打包

完成后，**把所有引用到的外部文档/规范/博客整理成 md** 放到 `./docs/references/`，每篇含：标题、来源 URL、核心要点摘要（用于离线查阅）。至少覆盖：

- MCP 规范与 **SEP-835**（工具级 scope）
- OAuth 2.1 token exchange / On-Behalf-Of
- SPIFFE/SPIRE 概念
- Vault/OpenBao 的 Barrier / Seal-Unseal / Dynamic Secrets / Lease 设计文档
- Cerbos / Casbin 策略模型文档
- Nacos MCP Registry / 配置热更新文档
- 国密 SM2/SM3/SM4 标准与合规库资料

最后**把整个 `./docs/` 目录打包成 `custos-design-docs.zip`** 交付，并在仓库根目录写一份 `README.md` 索引所有文档。

> 注意：`docs/references/` 里只放**你自己整理的中文摘要 + 链接**，不要整段复制受版权/许可保护的原文。

---

## 7. IP / 许可证红线（务必遵守）

- 你是在**学习架构与设计思想**，不是搬运代码。**严禁把竞品源码复制进 Custos**，尤其 **Vault（BSL 1.1）** 与 **OpenBao（MPL-2.0，文件级 copyleft）**。
- Custos 自身代码必须 **100% 原创**，计划以 **Apache-2.0** 开源。
- 若某设计点受到特定项目启发，在设计文档里注明「灵感来源」即可，但实现自己写。
- 在 `00-synthesis.md` 附一张**许可证合规表**：每个被研究项目的许可证、对我们的约束、我们的应对。

---

## 8. 沟通与执行规范

1. 先给**工作计划**让我确认，再执行。
2. 每完成一个阶段，输出该阶段的 md 并简述结论，等我反馈再继续（除非我说连续执行）。
3. 文档里多画图（mermaid/ASCII）、多用表格、给可落地的细节而非空话。
4. 遇到设计岔路口（如引擎语言、解封方式、存储后端），**列出选项 + 推荐 + 理由**让我决策，不要擅自定死。
5. 安全相关设计务必标注风险与缓解；`02-engine-crypto-design.md` 是重中之重，多花时间。

---

### 一句话总览

> 先 clone 上述竞品深度学习 → 综合提炼并钉死 Nacos-native 差异化 → 设计 Custos（Agent 身份·密钥·权限一体、自研引擎、支持国密、Nacos 控制面）的完整设计文档与 MVP 纵向线 → 把引用资料整理成中文 md 并打包交付。本阶段不写生产代码，先把设计做扎实。
