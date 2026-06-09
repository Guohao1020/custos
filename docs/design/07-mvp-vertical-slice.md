# 07 · MVP 纵向线（v0.1）模块清单 + WBS + 验收

> **定位**：把 PRD §7 的一条端到端纵向线落成**可实现的模块清单 + 工作分解（WBS）+ 验收标准**。目标：用最薄链路证明「身份 + 权限 + 密钥 + Nacos 秒级吊销」四件事，且**密钥不进 LLM**。
>
> 前提：`01`~`06`。

---

## 1. MVP 目标与演示链路（复述 PRD §7）

1. Claude/Codex 代表某用户，请求查询一个**只读数据库**。
2. Custos 签发该 Agent 的 **per-task 身份（JWT）**。
3. 策略层（策略存 **Nacos**）校验 Agent ∩ 用户 ∩ 资源 ∩ 风险 → **准/拒并给原因**。
4. 经纪层用自研引擎现场签发 **1h 只读 DB 凭证**，**secretless 执行查询、只回结果**。
5. 全程**审计留痕**（Agent+用户+任务+SQL 摘要，哈希链）。
6. 在 Nacos 改策略 → 该 Agent 访问**秒级被吊销**（可演示的对 Vault 的优势）。

**v0.1 引擎最小集**：Barrier 加密 + Shamir 解封 + MySQL 存储 + 动态 DB engine + 租约/撤销 + 哈希链审计；认证 JWT；策略基础 RBAC（存 Nacos）；MCP-native 经纪。

---

## 2. MVP 范围裁剪（做什么 / 不做什么）

| 纳入 v0.1 | 推迟（v0.2+） |
|---|---|
| Barrier(AES-256-GCM) + Shamir 解封 + MySQL 存储 | KMS 自动解封、国密套件实测、Raft HA |
| 动态 MySQL 只读凭证(1h/4h) + 租约/撤销 | AK/SK engine、KV、HTTP/内部系统经纪 |
| JWT 认证 + per-session 身份 | OBO 完整委托链、SPIFFE、mTLS（v0.2 OBO）|
| 基础 RBAC（策略存 Nacos）+ 可解释准/拒 | 完整 ABAC/风险分级、JIT 人工审批（v0.2）|
| Nacos 策略热更新 = 秒级吊销 | namespace 多租户全特性、MCP A2A |
| 哈希链审计 + 导出 | 完整 SIEM 对接、签名 checkpoint |
| MCP-native 经纪 secretless 查询 | Spring Starter 完整、CLI 完整 |
| 单节点 | HA / 强一致 |

---

## 3. 模块清单（映射到仓库目录）

| 模块 | v0.1 交付内容 | 依赖文档 |
|---|---|---|
| `engine/barrier` | AES-256-GCM Barrier（CipherSuite 抽象，仅 Intl 套件落地） | `02` |
| `engine/seal` | Shamir 解封（5/3）+ 启动 sealed + 一键 seal；KMS 接口留桩 | `02` |
| `engine/storage` | 存储抽象 + MySQL 实现（全密文） | `02` |
| `engine/lease` | Expiration Manager：TTL/续约/撤销/前缀 | `02` |
| `engine/audit` | 哈希链审计 + `verify` + 导出 | `02` |
| `identity` | JWT 认证 + per-session 令牌签发/校验 + Nacos 注册 | `03` |
| `authz` | jCasbin 基础 RBAC + Nacos Adapter/Watcher + 可解释准/拒 | `04` |
| `broker` | MCP server + secretless 查询 + 调引擎签发动态凭证 | `06` |
| `nacos` | 配置发布/订阅 + 秒级变更回调 + 实例注册 | `05` |
| `examples` | docker-compose（Nacos+MySQL+Custos）+ demo 脚本 + MCP 客户端样例 | — |
| `cli`（最小） | `custos operator unseal` / `policy put` / `audit verify` | — |

---

## 4. WBS（工作分解）

```mermaid
flowchart LR
    M0[里程碑0<br/>脚手架+CI] --> M1[里程碑1<br/>引擎内核可解封+存储+审计]
    M1 --> M2[里程碑2<br/>动态DB凭证+租约]
    M2 --> M3[里程碑3<br/>身份JWT+经纪secretless]
    M3 --> M4[里程碑4<br/>策略RBAC存Nacos+决策]
    M4 --> M5[里程碑5<br/>Nacos秒级吊销+审计闭环]
    M5 --> M6[里程碑6<br/>demo编排+验收]
```

| WBS | 任务 | 产出 | 依赖 |
|---|---|---|---|
| **0.1** 脚手架 | 多模块工程 + 构建 + CI（见 `08`） | 可构建骨架 | — |
| **0.2** CipherSuite | Intl 套件（AES-GCM/SHA-256/ECDSA）封装 BC | 加解密/签名可用 | `02` |
| **1.1** Barrier | 落盘前加密 + 格式(suite/版本/nonce) | 密文读写 | 0.2 |
| **1.2** Seal/Unseal | Shamir 5/3 + sealed 启动 | 解封流程 | 1.1 |
| **1.3** Storage(MySQL) | 存储抽象 + MySQL 全密文 | 持久化 | 1.1 |
| **1.4** Audit 哈希链 | 链式记录 + verify + 导出 | 防篡改审计 | 1.3 |
| **2.1** Lease | TTL/续约/撤销/到期回调 | 租约 | 1.3 |
| **2.2** 动态 DB engine | creation/revocation statements + 现场建/销账号 | 1h 只读凭证 | 2.1 |
| **3.1** 身份 JWT | 认证 + per-session 签发/校验 | 令牌 | 0.2 |
| **3.2** 经纪 secretless | MCP server + 代执行只回结果 | secretless 查询 | 2.2,3.1 |
| **4.1** Nacos 集成 | 配置发布/订阅 + 实例注册 | 控制面 | 0.1 |
| **4.2** 策略 RBAC | jCasbin + Nacos Adapter + 可解释准/拒 | 决策 | 4.1 |
| **5.1** 秒级吊销 | Nacos Watcher → enforcer 重载 + 端到端延迟测 | 吊销 | 4.2 |
| **5.2** 审计闭环 | 决策+访问全留痕 + 导出验证 | 审计 | 1.4,4.2 |
| **6.1** demo 编排 | docker-compose + 演示脚本 + 文档 | 可演示 | 全部 |

---

## 5. 验收标准（可测、对应演示链路）

| # | 验收项 | 通过标准 |
|---|---|---|
| AC1 | 引擎解封 | 启动 sealed；提供 3/5 Shamil 分片后 unsealed；缺片不可用 |
| AC2 | 落盘加密 | 直接读 MySQL 存储均为密文；改一字节 → 读取报完整性失败 |
| AC3 | 动态凭证 | 请求得 1h 只读账号；到期/吊销后该账号在 DB 被 DROP，无法再连 |
| AC4 | secretless | 全链路抓包/日志：**LLM 侧从不出现连接串/密码**，只见结果 |
| AC5 | 决策可解释 | 准/拒均返回命中策略 + 原因；拒绝可读 |
| AC6 | **秒级吊销** | 在 Nacos 改策略后，**≤ 数秒内**该 Agent 被拒（测量并记录 P95 延迟）|
| AC7 | 审计防篡改 | `custos audit verify` 通过；手工改一条历史 → verify 定位断链 |
| AC8 | demo 一键起 | `docker-compose up` 起 Nacos+MySQL+Custos，跑通演示脚本 |

> **MVP 成功定义**：演示链路全通，**AC6 秒级吊销可现场演示**（对 Vault 的可见优势），且 AC4 证明密钥不进 LLM。

---

## 6. 风险与缓解（MVP 局部）

| 风险 | 缓解 |
|---|---|
| 自研引擎缺陷 | 用 BC 库；1.x 里程碑加密/解封/审计单测 + 模糊测试；不赶进度跳安全 |
| Java 内存安全 | byte[] 清零 + mlock（`02` §8）；MVP 即落地基本内存卫生 |
| 秒级吊销不稳定 | 端到端延迟测试 + fail-safe（Nacos 不可用按最后策略，高危默认拒）|
| 范围蔓延 | 严格按 §2 裁剪，超出项进 v0.2 |

> **下一篇**：`08-repo-scaffold.md`（目录结构 / 选型 / 引擎语言 Java vs Go 论证 / CI / 依赖清单）。
