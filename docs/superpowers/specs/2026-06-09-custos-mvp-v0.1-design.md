# Custos MVP v0.1 实现级设计规格（接口契约级）

> **类型**：实现级 spec（接口契约级），供 writing-plans 直接消费产出实现计划。
> **校订**：2026-06-09 · **状态**：评审中 · **栈**：Java 21 / Spring Boot 3.x / Maven 多模块
> **依据**：纲领 `2026-06-09-custos-overall-architecture-spec.md`；详设 `docs/design/01~08`。**测试先行（TDD）**。
> 约定：以下接口签名为**契约草案**（Java 风格伪签名），命名/包路径以实现时为准；不锁定具体算法实现（留给编码）。

---

## 1. 目标与范围

**一条端到端纵向线**证明 5 件事：身份 + 权限 + 密钥 + Nacos 秒级吊销 + 密钥不进 LLM。演示链路见 `docs/design/07-mvp-vertical-slice.md` §1。

**纳入 v0.1**：Barrier(AES-256-GCM) · Shamir 解封(5/3) · MySQL 全密文存储 · 动态 MySQL 只读凭证(1h/4h) · 租约/撤销 · 哈希链审计 · JWT 身份 · jCasbin 基础 RBAC(策略存 Nacos) · Nacos 秒级吊销 · MCP-native secretless 查询 · 最小 CLI · docker-compose demo。
**不纳入（v0.2+）**：KMS 自动解封、国密套件实测、Raft HA、OBO 完整委托、ABAC/风险分级、JIT 审批、AK/SK engine、KV、X.509-SVID、完整 SDK/CLI。

---

## 2. MVP 架构切片

```mermaid
flowchart LR
    CLI["custos CLI"] -->|unseal/policy/audit| ENG
    MCPClient["Claude/Codex (MCP)"] -->|query_db(intent, userToken)| BR[broker MCP server]
    BR --> ID[identity: JWT]
    BR --> PDP[authz: jCasbin]
    PDP <-->|策略订阅+热推| NAC[(Nacos 配置)]
    BR --> ENG[engine: barrier/seal/storage/lease/audit]
    ENG --> MYSQL[(MySQL 全密文)]
    BR -->|临时只读凭证| DB[(只读业务 DB)]
```

---

## 3. 逐模块规格（接口契约 · 数据模型 · 配置 · 错误处理）

### 3.1 `engine/crypto` — CipherSuite（仅 Intl 套件落地）
```java
interface CipherSuite {
  byte suiteId();                                              // 0x01 = intl
  byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad);   // AES-256-GCM, 96-bit nonce
  byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad);  // 校验 GCM tag, 失败抛 IntegrityException
  byte[] hash(byte[] data);                                   // SHA-256
  byte[] hmac(byte[] key, byte[] data);                       // HMAC-SHA-256
  KeyPair genSignKey(); byte[] sign(PrivateKey k, byte[] d); boolean verify(PublicKey k, byte[] d, byte[] sig); // ECDSA P-256
}
```
- 实现 `IntlSuite`（BouncyCastle）。`GmSuite` 留接口不实现（v0.2+）。
- 错误：tag 校验失败 → `IntegrityException`（视为篡改，中止）。

### 3.2 `engine/barrier` — Barrier
```java
interface Barrier {
  byte[] seal(byte[] plaintext);     // 用当前 keyring 版本加密 → [suiteId|keyVersion|nonce|ct+tag]
  byte[] open(byte[] ciphertext);    // 解析版本 → 解密 + 校验
}
```
- 落盘格式：`suite_id(1) | key_version(4) | nonce(12) | ciphertext+tag`。
- 依赖 `CipherSuite` + 当前 barrier keyring（解封后在内存）。

### 3.3 `engine/seal` — SealManager（Shamir）
```java
interface SealManager {
  InitResult init(int shares, int threshold);   // 默认 5/3 → 生成 master key + N 个 unseal 分片(仅此一次返回)
  UnsealStatus submitUnsealKey(byte[] share);   // 累积分片; 达阈值 → 重建 unseal key → 解密 master → 解密 keyring → unsealed
  void seal();                                  // 丢弃内存 master/barrier key
  SealStatus status();                          // sealed? progress n/threshold
}
```
- 启动默认 **sealed**；未解封时除 `status/submitUnsealKey` 外的引擎操作抛 `SealedException`。
- Shamir 用审计库实现（GF(256)），**不自写**。

### 3.4 `engine/storage` — Storage（MySQL 全密文）
```java
interface Storage {                 // 所有 value 落盘前经 Barrier
  Optional<byte[]> get(String key);
  void put(String key, byte[] value);   // value = barrier.seal(原文)
  void delete(String key);
  List<String> list(String prefix);
}
```
- 实现 `MySqlStorage`（JDBC/MyBatis）。表见 §4。

### 3.5 `engine/lease` — LeaseManager
```java
interface LeaseManager {
  Lease register(String resourcePath, Duration ttl, Revoker revoker); // 返回 lease_id
  Lease renew(String leaseId, Duration increment);                    // increment 从当前时间起算
  void revoke(String leaseId);                                        // 调 revoker + 标记失效
  int revokePrefix(String prefix);                                    // 前缀批量吊销
}
interface Revoker { void revoke(Lease lease); }   // 如动态 DB engine 的 DROP USER
```
- 后台扫描到期 → 自动 `revoke`。撤销失败 → 重试 + 告警（不静默）。

### 3.6 `engine/audit` — 哈希链审计
```java
interface AuditLog {
  void append(AuditRecord r);     // 计算 prev_hash + chain_hash, 敏感字段 HMAC 脱敏, 只追加
  VerifyResult verify();          // 重算链, 返回首个断裂 seq 或 OK
}
```
- 记录结构见 `docs/design/02-engine-crypto-design.md` §11；链 `chain_hash_n = H(prev_hash || canonical(record_n))`。
- 审计**先于**把结果返回客户端。导出 ELK/OTel（MVP：JSON 行 + 可选 OTel）。

### 3.7 `identity` — JWT 身份（MVP：不含完整 OBO）
```java
interface Authenticator { Principal authenticate(Credential c); }   // MVP: OIDC/JWT, K8s SA
interface TokenService {
  ScopedToken issue(Principal p, Set<String> scope, Duration ttl);  // 签 per-session JWT (ECDSA)
  Claims verify(String jwt);                                        // 校验签名/exp/黑名单
}
```
- JWT claims（MVP）：`sub`(SPIFFE 风格 `custos://<td>/agent/<id>/session/<sid>`)、`scope`、`aud`、`exp`(≤15min)、`act`(预留 OBO)。
- 吊销：身份/会话黑名单存 Nacos 配置 → 热推；`verify` 查黑名单。
- 签名私钥经 `engine` KeyManager（受 Barrier 保护），仅签名瞬间在内存。

### 3.8 `authz` — PDP（jCasbin 基础 RBAC + 可解释）
```java
interface Pdp { Decision decide(DecisionRequest req); }            // effect + matchedPolicy + reason
interface PolicyAdapter { void loadFrom(String ns, String dataId); } // jCasbin Adapter 从 Nacos 读
interface PolicyWatcher { void onChange(Runnable reload); }         // Nacos 变更 → enforcer 重载
```
- MVP model.conf：RBAC + 工具级 `obj=tool:<server>/<tool>`、`act=read`（`keyMatch2`）。完整 PERM 见 `04` §2。
- **默认拒绝**；`Decision` 含命中 policy/rule 与原因（可解释，写审计）。
- 策略来源 = Nacos 配置（DataId=`custos-policy-<ns>`）。

### 3.9 `broker` — MCP server + secretless
```java
@McpTool("query_db")
Result queryDb(QueryIntent intent, String userToken);   // 1) verify token 2) PDP.decide 3) 准→签发1h只读凭证 4) 经纪执行 5) 只回结果 6) 审计
interface CredIssuer { LeasedCred issueDbReadonly(String role); } // 调 engine 现场建账号 + lease
```
- **secretless**：凭证仅在 broker 进程内，**绝不进入返回给 LLM 的结果**。
- 只读语句白名单/解析，防注入/越权语句。错误：决策拒 → 返回结构化拒绝原因（不泄信息）。

### 3.10 `nacos` — ControlPlane
```java
interface ControlPlane {
  void publish(String ns, String dataId, String content);
  String subscribe(String ns, String dataId, Consumer<String> onChange);  // gRPC 长连接秒级
  void register(ServiceInstance i); List<ServiceInstance> discover(String svc);
}
```

### 3.11 `cli` — 最小 CLI
- `custos operator init` / `unseal` / `seal` / `status`
- `custos policy put|get`（写/读 Nacos 策略）
- `custos audit verify`（链完整性）

---

## 4. 数据与存储 schema（MySQL，值列全密文）

| 表 | 关键列 | 说明 |
|---|---|---|
| `custos_keyring` | `id`, `key_version`, `wrapped_key`(密文：被 master key 加密), `created_at`, `active` | barrier keyring 多版本 |
| `custos_seal_config` | `id`, `shares`, `threshold`, `wrapped_master`(被 unseal key 加密), `seal_type` | 解封配置 |
| `custos_storage` | `skey`(PK), `svalue`(LONGBLOB, barrier 密文), `updated_at` | 通用密文 KV |
| `custos_lease` | `lease_id`(PK), `resource_path`, `issued_at`, `ttl_sec`, `expire_at`, `revoked`, `meta`(密文) | 租约 |
| `custos_audit` | `seq`(PK 自增), `ts`, `actor`, `task`, `resource`, `action`, `decision`, `result_digest`, `sensitive_hmac`, `prev_hash`, `chain_hash` | 哈希链，只追加 |
| `custos_dyn_role` | `role`, `creation_stmt`, `revocation_stmt`, `default_ttl`, `max_ttl` | 动态 DB 角色（连接配置密文存 storage）|

> master/unseal key 永不落表明文；`custos_keyring.wrapped_key`/`custos_seal_config.wrapped_master` 均为密文。

---

## 5. 关键时序（MVP）

1. **解封**：`init`(一次性返回分片) → 重启后 `submitUnsealKey` ×3 → 重建 master → 解 keyring → unsealed。
2. **动态凭证 + secretless**：`queryDb` → token verify → PDP 准 → `issueDbReadonly`(CREATE USER+GRANT SELECT, lease 1h) → broker 执行查询 → 只回结果 → 审计。
3. **秒级吊销**：CLI/管理员改 Nacos 策略 → gRPC 热推 → `PolicyWatcher` 重载 → 下次 `decide` 即拒（测 P95 延迟）。
4. **审计 verify**：`audit verify` 重算链 → OK / 定位断裂 seq。

完整时序图见 `docs/design/01` §4、`05` §2。

---

## 6. 错误处理与失败模式

| 场景 | 行为 |
|---|---|
| 未解封时请求引擎操作 | 抛 `SealedException`，拒绝 |
| Barrier 解密 tag 失败 | `IntegrityException`，中止该读，告警 |
| Nacos 不可用 | 用本地缓存的最后策略 **fail-safe**；高危/未知默认**拒**；恢复后重新订阅 |
| 租约撤销失败（DROP USER 失败）| 重试 + 标记 + 告警，不静默成功 |
| 缺 Shamir 分片 | `status` 显示进度 n/threshold，保持 sealed |
| 决策拒绝 | 返回结构化原因（不泄露资源细节）+ 审计 |

---

## 7. 配置项（`application.yml` 关键）
```yaml
custos:
  engine:
    storage: { type: mysql, url: ..., username: ..., password-ref: env:CUSTOS_DB_PWD }  # 引导账号经环境注入
    seal:    { type: shamir, shares: 5, threshold: 3 }
    cipher-suite: intl        # intl | gm(国密, v0.2+)
  nacos:
    server-addr: nacos.infra.svc:8848
    namespace: ${CUSTOS_NS}
    policy-data-id: custos-policy
  identity:
    token-ttl: 15m
    issuer: custos
  broker:
    db-readonly-role: orders-ro
    cred-ttl: 1h
```

---

## 8. 测试策略（TDD：先写测试再实现）

| 层 | 用例（红→绿→重构） |
|---|---|
| **单元** | CipherSuite 加解密往返/篡改 tag 报错；Shamir 切分-重建/缺片失败；Barrier 格式版本解析；哈希链 append + verify + 篡改检测；Lease TTL/续约/前缀撤销；JWT 签发/校验/过期/黑名单；jCasbin 决策准/拒 + 可解释 |
| **集成**（Testcontainers）| MySQL 全密文读写（直查表确认密文）；Nacos 配置发布→订阅热推；策略变更→重载 |
| **端到端** | docker-compose 起 Nacos+MySQL+Custos；跑 demo：MCP query_db → secretless 结果；改策略→秒级拒；到期 DROP USER |
| **安全/负路径** | 抓链路证明 LLM 侧无凭证；未解封拒操作；缺片不可用；篡改审计→verify 断裂；只读语句白名单拦截写语句 |
| **模糊测试** | Barrier 解析、审计 canonical 序列化（jqf/JUnit fuzz）|

> 引擎加解密/解封/审计为安全核心，**必须 test-first 且覆盖负路径**。

---

## 9. 验收映射（AC → 测试）

| AC | 验收 | 对应测试 |
|---|---|---|
| AC1 解封 | 3/5 分片解封，缺片不可用 | 单元(Shamir) + 端到端(unseal) |
| AC2 落盘加密 | 直查 MySQL 为密文；改字节报错 | 集成(密文) + 单元(Integrity) |
| AC3 动态凭证 | 1h 只读账号，到期 DROP | 端到端 + 集成(lease) |
| AC4 secretless | LLM 侧无连接串/密码 | 安全/负路径 |
| AC5 可解释 | 准/拒附命中策略+原因 | 单元(PDP) + 端到端 |
| AC6 秒级吊销 | 改策略≤数秒被拒（测 P95）| 端到端 + 延迟测 |
| AC7 审计防篡改 | verify 通过；改历史定位断链 | 单元(审计) |
| AC8 demo 一键起 | compose up 跑通 | 端到端 |

---

## 10. 里程碑（WBS，详见 `07` §4）

M0 脚手架+CI → M1 引擎(CipherSuite/Barrier/Seal/Storage/Audit) → M2 Lease+动态DB → M3 身份JWT+经纪secretless → M4 策略RBAC存Nacos+决策 → M5 Nacos秒级吊销+审计闭环 → M6 demo编排+验收。**每里程碑以其 AC 测试通过为完成定义。**

---

## 11. 非目标（v0.1 明确不做，避免范围蔓延）

KMS 自动解封 · 国密套件实测 · Raft HA · 完整 OBO 委托链 · ABAC/风险分级 · JIT 人工审批 · AK/SK engine · KV engine · X.509-SVID · mTLS · 多租户全特性 · MCP A2A · 完整 SDK/CLI · SIEM 深度对接。以上进 v0.2~v0.4（见纲领 spec §8）。
