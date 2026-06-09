# Custos 生产架构规格（Production Architecture Spec）

> **类型**：生产化架构蓝图（高扩展 · 解耦）。在已交付的 v0.1（engine/identity/authz/broker/app 五模块、`mvn -B verify` 全绿）之上，
> 定义把 Custos 收敛为「模块化单体 + SPI 插件」生产形态的架构、扩展点、运行时宿主与路线图分解。
> **校订**：2026-06-09 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：纲领见 `2026-06-09-custos-overall-architecture-spec.md`；v0.1 实现级 spec 见 `2026-06-09-custos-mvp-v0.1-design.md`；详设见 `docs/design/`。
> **本 spec 引用而非重复**纲领与详设；只补「生产形态如何落地」这一层。

---

## 1. 目标与范围

把 v0.1 的纵向线生产化为**高扩展、解耦**的产品形态，且不牺牲自托管的运维简单性。

- **目标**：① 扩展点 SPI 化（算法套件、存储、密钥引擎、认证、控制面、审计、传输、解封可插拔）；② 清晰的单向依赖与边界（编译期可验证）；③ 一个启动即 sealed、可端到端运行的运行时宿主，让 docker-compose demo 真正跑通 AC1–AC8；④ 把路线图 v0.2–v0.4 分解为各自 spec→plan 的子项目。
- **非目标（本 spec 不展开，留各子项目）**：OBO/ABAC/AK·SK/KV/HA-Raft/SPIFFE 的具体实现；微服务化拆分；为"将来可能的第二实现"提前炸裂 api/impl 模块（YAGNI）。

部署/耦合拓扑：**模块化单体 + SPI 插件**（已定）——单个可部署 Custos 服务，各层同进程但代码级严格解耦；扩展靠 SPI（`ServiceLoader`）+ Spring 自动装配双轨；`engine` 内核保留进程隔离后路（ADR-1）。

---

## 2. 分层与依赖拓扑（三圈，单向向内）

```
┌──────────────────────────────────────────────────────────┐
│ Transport 面   MCP(stdio/SSE) · REST admin · gRPC · SDK    │  ← 对外
├──────────────────────────────────────────────────────────┤
│ 编排层  BrokerService(PEP) · OperatorService · PolicyService│  ← 组合 SPI
├──────────────────────────────────────────────────────────┤
│ SPI 内核  engine · identity · authz · secrets（纯接口契约）  │  ← 能力
└──────────────────────────────────────────────────────────┘
        依赖只允许自上而下；同层 sibling 互不依赖
```

- **engine**：纯 Java，无 Spring/MCP/Nacos/业务依赖；核心 SPI 经 `ServiceLoader` 发现。保进程隔离后路。
- **identity / authz / secrets**：只依赖 `engine` 暴露的 SPI 契约与各自算法/框架库；彼此不互依赖，由编排层组合。
- **编排层**：`BrokerService`（PEP，已存在）、`OperatorService`（解封/运维）、`PolicyService`（策略读写 Nacos）。
- **Transport 面**：多形态、可独立开关；secretless 红线不变（只回结果，绝不回凭证）。

---

## 3. SPI 扩展目录（"高扩展"核心）

| SPI（扩展点） | 职责 | v0.1 实现 | 路线图实现 | 选择/编排 |
|---|---|---|---|---|
| `CipherSuite` | 算法套件 | `IntlSuite`(AES-GCM/SHA/ECDSA) | `GmSuite`(SM4/SM3/SM2)、`KmsSuite` | `suiteId` 选 |
| `Unsealer` | 解封策略 | Shamir(`DefaultSealManager`) | KMS 自动解封 | config |
| `Storage` | 元数据全密文存储 | `JimmerStorage`(MySQL) | TiDB/OceanBase/Raft | config |
| `SecretsEngine` | 密钥引擎（按 path 挂载） | `DynamicDbCredentials`(DB) | AK·SK、KV、更多 DB | type/path 注册表 |
| `Authenticator` | 外部主体→Principal | （v0.2） | JWT/OIDC/SPIFFE/X.509 | 责任链 |
| `TokenService` | 令牌签发/校验 | `JwtTokenService`(ES256) | X.509-SVID、OBO/STS | config |
| `Pdp` | 决策 | `CasbinPdp`(RBAC) | ABAC/风险分级 | 装饰链 |
| `ControlPlane` | 策略发布/订阅 | `NacosControlPlane` | 接口留（护城河定位不替换） | config |
| `AuditSink` | 审计落地 | `HashChainAuditLog`(MySQL) | SIEM/OTel 导出 | 多 sink 扇出 |
| `LeaseManager` | 租约 | `DefaultLeaseManager` | 集群化(Raft) | config |
| `Transport` | 对外接口面 | MCP stdio(`McpQueryToolServer`) | REST admin、MCP SSE/HTTP、gRPC、SDK starter | 多开关 |

> 扩展缝 = **接口 + ServiceLoader/Spring 装配**。不为"将来可能有第二实现"提前拆 api/impl 模块；仅 `engine` 进程隔离后路值得硬隔离，其余等真出现第二实现再拆（YAGNI）。

---

## 4. 双轨装配机制

- **轨一 · `ServiceLoader`（engine 内核）**：`engine` 不引入 Spring。核心 SPI（`CipherSuite` 等）经 `META-INF/services` 发现，使 engine 可被抽到独立进程而不带 Spring。
- **轨二 · Spring 自动装配（宿主层）**：宿主用 `@ConditionalOnProperty`/`@ConditionalOnMissingBean` 按 `application.yml` 选择并编排实现（哪个 `CipherSuite`、哪个 `Storage`、挂载哪些 `SecretsEngine`、开哪些 `Transport`），并提供 `custos-spring-boot-starter`（路线图 SDK 子项目）。
- **注册表**：`SecretsEngineRegistry`（按 path 挂载多个引擎）、`TransportRegistry`（按开关启用多个传输）、`AuditSink` 扇出。

---

## 5. 运行时宿主拓扑 + transport 面 + 解封流

- **宿主**：单个 Spring Boot 服务（`custos-host`，由现 `app` 演进），**启动即 sealed**；Spring 自动装配在 SPI 之上接好 engine(MySQL 存储/seal/barrier) + identity + authz(Nacos `PolicyWatcher`) + broker + audit。
- **解封流（解决内存态共享）**：长驻宿主进程持有内存解封态（master/keyring 仅在内存，分片不落盘、不进 env）。operator 经 **REST admin** 提交分片解封：
  - `POST /operator/init {shares,threshold}` → 一次性返回 N 个分片
  - `POST /operator/unseal {share}` ×threshold → 重建 master → 解 keyring → unsealed
  - `GET /operator/status` → `{sealed, progress, threshold}`
  - 未解封时，除 status/unseal 外的引擎操作（含 `/query_db`）一律 `SealedException`→HTTP 409。
  - admin 面绑回环 + Bearer Token（`CUSTOS_ADMIN_TOKEN`），不对公网暴露。
- **策略/审计运维**：`POST /policy {dataId,content}`（写 Nacos）、`GET /audit/verify`（哈希链校验）。
- **查询面**：`POST /query_db {tool,schema,sql,userToken}` → `BrokerService.queryDb`（verify→decide→issue→secretless 执行→即用即焚撤销）。
- **MCP-native transport**：`McpQueryToolServer` 接同进程 `BrokerService` 暴露 `query_db`；stdio 供 agent 客户端就近启动，SSE/HTTP（路线图）供远程。**只回结果，绝不回凭证。**

---

## 6. 解耦铁律（编译期可验证）

1. 依赖方向 `transport → 编排 → SPI 内核`，绝不反向；同层 sibling 互不依赖。
2. `engine` 不依赖 Spring/MCP/Nacos/任何业务模块。
3. `authz` 不持密钥；`broker` 不判策略（调 `Pdp`）；`ControlPlane`（Nacos）只做控制面、绝不存密钥；`engine` 不懂业务/MCP。
4. 任何凭证/明文密钥不出编排层边界进入 transport 返回值（secretless 红线）。
5. 上述靠模块依赖图保证（Maven 模块依赖方向 + 评审）；CI 可加依赖方向校验（ArchUnit，路线图）。

---

## 7. 路线图分解（子项目，各自 spec→plan→实现）

| # | 子项目 | 对应路线图 | 依赖 |
|---|---|---|---|
| **P-Foundation** | **生产基座**（本轮转 plan）：SPI 正式化 + `custos-host` + OperatorService/REST admin + MCP transport + `custos-cli` + 端到端 IT/demo | v0.1 收口生产化 | v0.1 |
| P-OBO | OBO 委托 + STS token-exchange + `Authenticator` 链 | v0.2 | Foundation |
| P-ABAC | ABAC/风险分级 PDP（`Pdp` 装饰链） | v0.2 | Foundation |
| P-AKSK | AK·SK secrets engine + 轮换（`SecretsEngine`） | v0.2 | Foundation |
| P-KV | KV / 更多 DB secrets engine | v0.3 | AKSK |
| P-HA | HA：Raft/JRaft 强一致 `Storage`/`LeaseManager` | v0.3 | Foundation |
| P-SPIFFE | SPIFFE 认证 + X.509-SVID（`Authenticator`/`TokenService`） | v0.3 | OBO |
| P-SDK | `custos-spring-boot-starter` + CLI 完善 | v0.3 | Foundation |
| P-Hardening | 国密套件实测、内存加固、性能压测、外部安全审计 | v0.4 | 全部 |

---

## 8. 第一子项目「生产基座」交付范围（转 writing-plans）

1. **SPI 正式化**：扩展点接口归位到各层 `spi` 包，该用 `ServiceLoader` 的加 `META-INF/services`；把 `DynamicDbCredentials` 抽象为 `SecretsEngine` SPI（`issue(path,ttl)→IssuedCred`、`revoke(leaseId)`）+ 按 path 挂载的 `SecretsEngineRegistry`。
2. **运行时宿主 `custos-host`**（由 `app` 演进）：Spring Boot 服务，启动即 sealed；Spring 自动装配按 `application.yml` 在 SPI 之上接好 engine/identity/authz/broker/audit。
3. **OperatorService + REST admin API**：`/operator/{init,unseal,seal,status}`、`/policy`、`/audit/verify`、`/query_db`；回环 + Bearer Token 保护；未解封 409。
4. **MCP Transport**：`McpQueryToolServer` 接 `BrokerService`（同进程），供 agent 客户端 stdio 启动。
5. **最小 `custos-cli`**（picocli）：`operator/policy/audit` 子命令打 REST admin，使 demo runbook 的命令为真命令。
6. **端到端可跑 + IT**：docker-compose（Nacos+MySQL+Custos host）真正启动；`custos-host` 集成测试（Testcontainers MySQL，Nacos 用环境门控或内存 `ControlPlane`）：启动→解封→发策略→`/query_db` 准/拒→审计 verify→断言 secretless；更新 `examples/demo.md` 用真命令对齐 AC1–AC8。

---

## 9. 测试与验收策略

- 沿用 TDD + Testcontainers；安全核心（解封/加解密/审计/secretless）必须覆盖负路径。
- 生产基座新增**宿主级集成测试**：以 `@SpringBootTest` 启动 `custos-host`，Testcontainers MySQL，走完 init→unseal→policy→query(准/拒)→audit verify 全链路，并断言返回不含凭证、未解封请求 409。
- 验收沿用 v0.1 的 AC1–AC8（`examples/demo.md`），但由真命令（CLI/curl 打 REST admin + MCP）驱动，docker-compose 一键起可复现。

---

## 10. 非目标 / YAGNI

- 不在生产基座阶段拆 api/impl 多 jar、不上微服务、不做 HA/OBO/ABAC/AK·SK 具体实现（各自子项目）。
- 不引入第二个 `ControlPlane`/`Storage` 实现（接口留缝即可，等真需要再加）。
- admin API 仅本地回环 + Token；不做完整 RBAC 控制台/UI（路线图 SDK/控制台另议）。
