# SPIFFE / SPIRE — 工作负载（非人类）身份的事实标准

> **一句话定位**：SPIFFE 是"给工作负载/机器一个可验证的密码学身份"的开放标准；SPIRE 是它的生产级参考实现（CNCF 毕业项目，**Apache-2.0，可放心借鉴**）。它解决的正是 Custos 身份层的母题：**在不预置长期密钥的前提下，给一个临时工作负载签发可验证、短时、可轮换的身份**。
>
> 本笔记基于本地克隆 `research/spire`（`doc/SPIRE101.md`、各 nodeattestor/workloadattestor/keymanager 插件文档、`pkg/server`、`pkg/agent`）。

---

## 1. 它解决什么问题 & 核心架构

传统做法给每个服务塞一份长期密钥/证书 → 难轮换、易泄漏、泄漏后难追溯。SPIFFE 的答案：用**两段式 attestation（证明）**把"工作负载是谁"绑定到平台原生属性（K8s SA、AWS 实例身份、Unix uid…），据此签发短时 **SVID**，工作负载**从不持有长期私钥**。

```mermaid
flowchart TB
    subgraph node[一台节点 / Pod]
      WL[工作负载] -->|Workload API over UDS| AG
      AG[SPIRE Agent]
    end
    SRV[SPIRE Server\n信任域 CA / 签发权威]
    DS[(DataStore\nregistration entries)]
    AG -->|① Node Attestation\n证明"我这台节点是谁"| SRV
    SRV --> DS
    AG -->|② 拉取本节点应签发的 entries| SRV
    WL -. ③ Workload Attestation\n(uid/gid、k8s pod 标签) .-> AG
    AG -->|④ 签发 X.509-SVID / JWT-SVID| WL
    SRV -->|UpstreamAuthority / KeyManager| KMS[(disk / memory / AWS-KMS / Vault)]
```

**两个进程**（`doc/SPIRE101.md` 印证）：
- **SPIRE Server**：每个**信任域（trust domain，如 `spiffe://example.org`）**一个；是签发权威（CA），持有 registration entries（DataStore SQL）。
- **SPIRE Agent**：每节点一个；向 Server 做节点证明后，对本机工作负载做工作负载证明，并经 **Workload API（Unix Domain Socket）** 把 SVID 交给工作负载。

---

## 2. 关键机制如何实现（含源码/文档定位）

### 2.1 SPIFFE ID 与 SVID
- **SPIFFE ID**：形如 `spiffe://<trust-domain>/<path>` 的 URI，是身份的名字（如 `spiffe://example.org/workload`）。
- **SVID（SPIFFE Verifiable Identity Document）**：身份的可验证凭证，两种载体——
  - **X.509-SVID**：把 SPIFFE ID 放进证书的 URI SAN；用于 mTLS。
  - **JWT-SVID**：把 SPIFFE ID 放进 JWT（`sub`），带受众 `aud`；用于无 mTLS 通道的场景。
- **短 TTL + 自动轮换**：SVID 默认短寿命，Agent 持续重签——"短时凭证"在身份层的体现。

### 2.2 两段式 Attestation（Custos OBO/身份签发的关键借鉴）
- **Node Attestation**（`doc/plugin_agent_nodeattestor_*`）：Agent 向 Server 证明节点身份。方式丰富：`join_token`（一次性 nonce，TTL 默认 600s）、`k8s_psat`（K8s 投影 SA token）、`aws_iid`/`azure_msi`/`gcp_iit`（云实例身份）、`x509pop`/`tpm_devid`（硬件/证书）。**核心思想：用平台已有的可信信号换身份，不预置密钥。**
- **Workload Attestation**（`doc/plugin_agent_workloadattestor_*`）：Agent 用内核/平台属性鉴别本机进程身份——`unix`（uid/gid）、`k8s`（pod 标签/SA）、`docker`、`systemd`。例如 `SPIRE101` 里把 `unix:uid:1001` 作为 **selector** 绑定到 `spiffe://example.org/workload`。
- **Registration Entry**（`spire-server entry create -parentID ... -spiffeID ... -selector ...`）：声明"满足这些 selector 的工作负载 → 这个 SPIFFE ID"，是 attestation 的策略表。

### 2.3 签名密钥管理与上游 CA
- **KeyManager 插件**（`doc/plugin_server_keymanager_*`）：签发用的密钥可存 `memory`/`disk`，或托管到 **AWS KMS / Azure Key Vault / GCP KMS / HashiCorp Vault**。→ 与 Custos "master key 用 KMS 自动解封"的思路相通。
- **UpstreamAuthority**：SPIRE CA 可挂到企业既有 CA 之下，形成信任链。
- **Federation**：不同信任域之间交换 trust bundle，实现跨域互信。

### 2.4 外部安全审计（`doc/cure53-report.pdf`）
- SPIRE 仓库内置 **Cure53 第三方安全审计报告**。→ 对 Custos PRD "v0.4 前外部安全审计"是直接范本：**持有/签发身份的系统，外部审计是上生产的必经环节**。

---

## 3. 在 AI Agent 场景下的不足 / 与 Nacos 生态的脱节

| 维度 | SPIRE 的局限（站在 Custos 立场） |
|---|---|
| **只管身份，不管密钥/权限** | SPIRE 给"身份"，不签发 DB 凭证、不做 secretless 经纪、不做工具级授权——只是 Custos 三层中的"身份层"一块 |
| **无 OBO / 委托语义** | 工作负载身份是"它自己是谁"，没有"代表某用户、取用户∩Agent 最小权限"的派生身份概念 |
| **Agent 概念不同** | SPIRE 的 "Agent" 是每节点的守护进程，不是"AI Agent / 每会话临时身份"；要把 per-session、海量临时身份映射上去需自行设计 |
| **运维偏重** | 每信任域一 Server、每节点一 Agent、插件众多，部署链路长 |
| **与 Nacos 脱节** | DataStore 用 SQL、配置用 HCL 文件；不消费 Nacos 注册/配置，拿不到"热更新=秒级吊销"护城河 |
| **MCP/工具无感** | 不懂 MCP，不做工具/动作级 scope |

---

## 4. 可借鉴的设计 vs 要避免的坑

| ✅ 借鉴（Apache-2.0，可深度借鉴思想） | ⚠️ 要避免 / 改造 |
|---|---|
| **不预置密钥的两段式 attestation**：用平台可信信号（K8s SA / 云实例身份 / Unix uid）换身份 | 不必引入"每节点 Agent 守护进程"的重型拓扑；Custos 走 SDK/MCP 注入 |
| **SVID 双载体**：X.509-SVID（mTLS）+ JWT-SVID（无 mTLS）——Custos 身份令牌可同样双载体 | trust domain/federation 的复杂度首版不做 |
| **短 TTL + 持续轮换** 的身份生命周期 | DataStore=SQL、配置=HCL → Custos 改走 **Nacos 注册 + 配置** |
| **签名密钥托管 KMS** 的 KeyManager 抽象 | — |
| **SPIFFE ID 命名规范**（URI 化身份）可直接借鉴为 Custos Agent 身份命名 | — |
| **内置外部安全审计（Cure53）** 的工程实践 → Custos v0.4 照做 | — |

**对 Custos 身份层（PRD ID1–ID3）的直接映射**：
- ID1 per-session 临时身份 ← SVID 短 TTL + SPIFFE ID 命名；
- ID2 多认证方法（OIDC/JWT/K8s SA/SPIFFE）← node attestor 插件谱系（尤其 `k8s_psat`）；
- ID3 OBO 委托 ← SPIRE **没有**，是 Custos 必须**新造**的能力（结合 OAuth2 token-exchange）。

---

## 5. 许可证与对 Custos 的约束

| 项 | 内容 |
|---|---|
| **许可证** | **Apache-2.0**（`research/spire/LICENSE`）——与 Custos 同许可，**最友好**：可放心借鉴设计，甚至在合规标注下复用思路与接口形态。 |
| **生态选择** | 可考虑直接**对接 SPIFFE 标准**（让 Custos 身份兼容 SPIFFE ID / SVID），作为 ID2 的"后续 SPIFFE 认证方法"（PRD 已列为后续）。但**首版不引入完整 SPIRE 部署**，只借鉴 attestation 与 SVID 思想，自研轻量身份签发。 |
| **自主可控视角** | SPIFFE 是开放标准、CNCF 治理，兼容它不损害自主可控；但完整 SPIRE 运维重，国内落地宜"借标准、轻实现"。 |

> **结论**：SPIRE 是 Custos **身份层**的设计灯塔——"**不预置密钥的 attestation + 短时可验证 SVID + URI 化身份命名**"几乎可整套借鉴**思想**（且 Apache-2.0 无许可顾虑）。但它只覆盖三层中的身份层，**不含密钥/权限/OBO/MCP/Nacos**；Custos 要在其身份思想之上，叠加 OBO 委托、Nacos 控制面与 secretless 经纪，并用轻量实现替代 SPIRE 的重型拓扑。
