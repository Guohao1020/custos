---
id: SPEC-M15-RESOURCE-ONBOARDING
type: spec
title: "v0.5 资源接入（Resource Onboarding）设计"
status: reviewing
date: 2026-06-11
desc: "企业快速接入数据库/中间件：运行时资源注册表 + 高权限密钥 Barrier 托管 + 多 DB 方言（hybrid 适配器/模板）"
---

# v0.5 资源接入（Resource Onboarding）设计

## 0 · 背景与动机

让企业快速对接内部数据库（先做好）/中间件/系统，需要在后台配置这些资源和它们的**真实高权限密钥**。
当前是 demo 反面教材：目标库 `custos.broker.target-jdbc-url` 硬编码在 `application.yml`，
高权限管理凭证是 compose 明文 `custos`/`custospwd`，`DynamicDbCredentials` 把 MySQL 的
`CREATE USER 'x'@'%'` 语法写死——无法对接第二个库。

本切片把这套升级为 Vault database-secrets-engine 模型：管理员运行时注册资源 + 配高权限密钥，
custos 用它现场签发即用即焚短时凭证，**高权限密钥全程经 Barrier 加密托管**——这是引擎核心价值
（密钥托管）的正面兑现，也是「不要 demo 思维」在接入层的落地。承接定位 spec
`2026-06-11-positioning-and-docs.md` §4.2，本 spec 细化为可实现的 v0.5。

## 1 · 已确认的关键决策

| # | 决策 | 取舍 |
|---|---|---|
| D1 | 资源记录（含加密的高权限凭证）**全进 `custos_storage`，Barrier 加密** | 自包含、不依赖 Nacos、复用 JimmerStorage；Nacos 配置同步留 v0.6 |
| D2 | v0.5 内置 **MySQL + PostgreSQL** 适配器，其余库走 **SQL 模板逃生口** | 复用已有两实现零额外依赖；Oracle/SQLServer 走模板，内置适配器按需后补 |
| D3 | **显式注册**（去硬编码 `target-jdbc-url`） | demo 加 AC0 展示真实接入流程；更干净无隐式魔法 |
| D4 | 一个资源支持**多个具名角色**（内置 `read-only` + 模板） | 对齐 Vault，模型正确；Agent 按 resource/role 请求 |

## 2 · 架构与组件

新包 `engine/src/main/java/io/custos/engine/resource/`（引擎层）+ app 的 REST/装配 + broker 解析。

| 组件 | 职责 | 依赖 |
|---|---|---|
| `ResourceRecord`（record） | 资源定义：name、dialect（mysql/postgresql/template）、jdbcUrl、adminUsername、adminPassword、`List<RoleDef>` | — |
| `RoleDef`（record） | 角色：name、kind（`BUILTIN_READONLY`/`TEMPLATE`）、creationStatements、revocationStatements、defaultTtlSeconds、schema | — |
| `ResourceStore` | 经 `Storage`（Barrier 加密）持久化资源记录：put/get/list/delete，key=`resource/<name>` | engine `Storage`、Jackson |
| `DbCredentialAdapter`（SPI） | `issue(Connection admin, RoleDef, Duration) → IssuedCred` + `revoke(Connection admin, String username)` | — |
| `MySqlAdapter` / `PostgresAdapter` | 内置方言：重构现有 `DynamicDbCredentials` / `PostgresDynamicCredentials` 的 DDL 进来 | JDBC |
| `TemplateAdapter` | 跑 RoleDef 的 creation/revocation 模板，占位符 `{{name}}`/`{{password}}`/`{{expiration}}` 替换 | JDBC |
| `ResourceSecretsEngine implements SecretsEngine` | 包一条 ResourceRecord：issue 时临时开 admin 连接→选适配器→签发→Zeroize 密码→关连接→登记租约 | ResourceStore、Barrier、LeaseManager、adapters |
| `ResourceManager` | register（试连校验→存→挂 registry）、list、get、unregister、rotateAdminKey | ResourceStore、SecretsEngineRegistry、AuditLog |

`SecretsEngine` SPI / `SecretsEngineRegistry`（mount/require）/ `IssuedCred` 已存在，复用。

## 3 · 数据流 + 密钥托管时序

**注册**：admin `POST /resources` → ResourceManager **试连校验**（用提供的 admin 凭证开一次连接）→
序列化 ResourceRecord → `Storage.put("resource/"+name, bytes)`（**Barrier 加密落库**）→
`registry.mount(name, new ResourceSecretsEngine(...))` → 审计 append(register)。**响应绝不回显 adminPassword**。

**签发**（查询时）：broker → `registry.require(resource).issue(role, ttl)` → ResourceSecretsEngine：
`Storage.get` → `Barrier.open` → 内存得 adminPassword → 开 admin JDBC 连接 → adapter 现场
`CREATE USER + GRANT`（或模板）→ **`Zeroize` 清密码字节** → 关连接 → 登记租约（到期 revoker DROP）→
返回 `v_ro_*` 短时凭证（**非** admin 凭证）。

**撤销**：租约到期或显式 → 重开 admin 连接 → adapter `DROP USER`。

**轮换高权限钥**：`POST /resources/{name}/rotate-admin {newAdminPassword}` → 载记录 → 改 adminPassword →
重新落库（Barrier 重加密）→ 审计。

**密钥托管不变式**：admin 密码静态只存在 Barrier 密文里；仅 issue/revoke/rotate 内存解出、
用后立即 Zeroize；从不进 IssuedCred、REST 响应、日志、Nacos。连接**按需开关**（不长持特权连接；连接池留 v0.6）。

## 4 · REST + CLI 面（admin-gated）

REST：app 新 `ResourceController`，`/resources/*` 加入 `AdminTokenFilter` 保护前缀（Bearer admin token）。

| 端点 | 作用 | 响应 |
|---|---|---|
| `POST /resources` | 注册资源 + 角色 | `{name, status}`，**无密码** |
| `GET /resources` / `GET /resources/{name}` | 列表 / 详情 | **脱敏**：name/dialect/jdbcUrl/roles/status，**无 adminPassword** |
| `DELETE /resources/{name}` | 注销（卸载 mount） | `{ok}` |
| `POST /resources/{name}/rotate-admin` | 轮换高权限钥 | `{ok}`，**无密码** |

注册 body 示例：
```json
{ "name": "appdb", "dialect": "mysql",
  "jdbcUrl": "jdbc:mysql://mysql:3306/appdb",
  "adminUsername": "custos", "adminPassword": "custospwd",
  "roles": [ { "name": "read-only", "kind": "BUILTIN_READONLY", "schema": "appdb", "defaultTtlSeconds": 3600 } ] }
```

CLI：`custos resource register|list|show|rm|rotate-admin`（picocli 子命令，包 REST）。

## 5 · broker 解析 + query_db 工具形状变化

`QueryIntent(tool, schema, sql)` 改为 `QueryIntent(tool, resource, role, sql)`，`role` 默认 `read-only`。
query_db 工具入参：`tool, resource, role?, sql, userToken`（`schema` 改名 `resource`，已确认）。
BrokerService：PDP 决策通过后 `registry.require(intent.resource()).issue(intent.role(), ttl)` →
`SecretlessQueryExecutor` 用签出的凭证连该资源跑 SQL → finally revoke → 审计。
- PDP 决策对象 v0.5 仍 `tool:` 级；**resource 传进审计**。资源级策略（`resource:appdb/read-only`）标为 v0.6 可选。

## 6 · demo / 测试迁移 + 新 AC

- **去硬编码**：删 `custos.broker.target-jdbc-url` 与 `db-readonly-schema`；OperatorService.assemble 改为
  解封后从 ResourceStore 载入所有资源、挂载各自引擎（无资源 = 空注册表）。
- **demo.md 新增 AC0**（查询前）：`custos resource register --name appdb --type mysql --jdbc-url jdbc:mysql://mysql:3306/appdb --admin-user custos --admin-password custospwd --role read-only`。
  流程变：解封 → 注册 appdb → 策略 → 签 token → query_db(`resource=appdb`)。
- **新增 AC9（密钥托管铁证）**：`SELECT svalue FROM custos.custos_storage WHERE skey='resource/appdb'` 为密文，
  grep 不到 `custospwd`；`resource list` 脱敏无密码；`rotate-admin` 后旧密文换新。
- **迁移面**：HostEndToEndIT / BrokerServiceIT / OperatorServiceTest setup 加注册步；mcp-custos.sh 解封后注册 appdb；
  compose init 不变（GRANT ALL 的 `custos` 用户即注册的高权限凭证，仍 G3 缺口）。
- **AUDIT-PREP 更新 G3**：高权限凭证已 Barrier 托管 ✓；权限仍过大（GRANT ALL），生产应换最小权限 admin 角色。

## 7 · 测试（TDD）

- `ResourceStoreIT`：Barrier 往返 + **断言落库 blob 不含明文密码**（密钥托管核心证明）。
- `MySqlAdapterIT` / `PostgresAdapterIT`（Testcontainers）：issue→账号存在+GRANT→revoke→DROP。
- `TemplateAdapter`：单元（占位符替换）+ IT（真库跑 creation/revocation）。
- `ResourceSecretsEngineIT`：载记录→签发→凭证可连可查→撤销→DROP；断言 IssuedCred/toString 不泄漏 admin 密码。
- `ResourceManagerIT`：register（校验连通）→mount→端到端签发；rotate-admin；unregister。
- `ResourceControllerIT`（Spring+Testcontainers）：POST 注册→GET list 脱敏→rotate→DELETE；无 token 401。
- 回归：BrokerServiceIT / HostEndToEndIT 带注册步，断言结果仍 secretless。
- 负路径：坏 admin 凭证注册→校验失败；查未注册资源→明确报错。

## 8 · 验收标准

- 经 REST/CLI 注册一个 MySQL 资源（含 admin 凭证），随后 query_db 用该资源签发即用即焚只读凭证、跑通查询、结果 secretless。
- `custos_storage` 里资源记录为密文，grep 不到 admin 密码；`GET /resources` 响应不含密码。
- 多 DB：同样流程对 PostgreSQL 资源跑通；模板角色对其一真库跑通自定义 creation/revocation。
- rotate-admin 后旧密文失效、新凭证可签发。
- `mvn -B clean verify` 全绿（含迁移后的回归 IT）。

## 9 · 不做（YAGNI）

- 不做 Nacos 配置同步资源元数据（v0.6）；不做 console 资源 GUI（v0.6）；不做连接池（按需开关，v0.6 优化）。
- 不做 Oracle/SQLServer 内置适配器（走模板）；不做中间件/系统引擎类型（DB 优先，后续 secrets-engine 类型）。
- 不做资源级 ABAC 策略（v0.6 可选）；不做高权限凭证的自动轮换调度（仅手动 rotate-admin）。
