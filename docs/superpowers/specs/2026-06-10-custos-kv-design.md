# Custos KV / 更多 secrets engine 设计规格（M10）

> **类型**：路线图子项目 **M10 / P-KV**（v0.3）设计。版本化 KV 引擎 + PostgreSQL 动态凭证（SecretsEngine 第三实现）。
> **校订**：2026-06-10 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec §3/§7；`docs/design/06-secrets-broker.md`。前置：M02(Storage/Barrier)、PF-T1(SecretsEngine SPI)、M09(AkSk 模式)。

---

## 1. 目标与范围

两件事：① **版本化 KV 引擎**——静态机密（API key、证书、配置密文）的密文存取，带版本历史；② **PostgreSQL 动态只读凭证**——`SecretsEngine` 第三实现，证明 DB 类引擎可按方言扩展。

- **纳入**：`KvEngine` 接口 + `StorageKvEngine`（落 `Storage`，永远 Barrier 密文）；`PostgresDynamicCredentials implements SecretsEngine`（Testcontainers PG 验证）。
- **非目标**：Oracle（镜像/许可重，推迟）；KV 的 TTL/租约（静态机密无租约语义）；KV 的 Web UI。

## 2. KvEngine（版本化 KV，密文落盘）

**KV 与 SecretsEngine 动词不同**（put/get/版本 vs issue/revoke），故独立接口；底层复用 `Storage` SPI——天然继承"值列全密文"。

```java
public interface KvEngine {
    long put(String path, byte[] value);                 // 写新版本，返回版本号(从1起)
    java.util.Optional<byte[]> get(String path);          // 最新版本
    java.util.Optional<byte[]> get(String path, long version);
    long currentVersion(String path);                     // 0 = 不存在
    void delete(String path);                             // 删全部版本+元数据
}

/** 键布局：kv/{path}#meta → 8字节BE当前版本；kv/{path}#v{n} → 数据。 */
public final class StorageKvEngine implements KvEngine {
    public StorageKvEngine(io.custos.engine.storage.Storage storage) { ... }
}
```
- 并发：单写者假设（宿主层串行化同 path 写），meta 读-增-写无锁；冲突治理留 HA(M11) 的 Raft 线性化。
- 测试：内存 `Storage` 替身（Map），纯单元——put 递增版本、get 最新/指定版本、delete 清空、密文性由 Storage 已有 IT 保证。

## 3. PostgresDynamicCredentials（SecretsEngine 第三实现）

镜像 `DynamicDbCredentials`（MySQL）的形态，type=`"db-readonly-postgres"`：
- issue：`CREATE ROLE v_ro_<hex> LOGIN PASSWORD '<hex>'` + `GRANT SELECT ON ALL TABLES IN SCHEMA public TO ...`，登记 `LeaseManager` 租约（撤销 → `DROP ROLE`）。
- revoke：经租约触发 `REASSIGN/DROP ROLE IF EXISTS`。
- 标识符仅 `[0-9a-f]`（防注入，同 MySQL 实现）。
- 测试：Testcontainers `postgres:16`（`org.testcontainers:postgresql:1.19.8`），IT 断言可查不可写、撤销后无法登录；engine pom 已钉 `api.version=1.40`。

## 4. 错误处理

| 场景 | 处理 |
|---|---|
| get 不存在 path/version | `Optional.empty()` |
| put 首版 | meta 不存在视为 0 → 写 v1 |
| PG CREATE/DROP 失败 | 抛 `IllegalStateException`（同 MySQL 实现） |

## 5. 测试策略

KV：纯单元（内存 Storage 替身）5+ 用例。PG：Testcontainers IT 2 用例（同 `DynamicDbCredentialsIT` 模式，连 PG 时默认库即目标库，无 MySQL 的 test 库陷阱）。

## 6. YAGNI

不做 KV 租约/TTL、不做 CAS 多写者、不做 Oracle、不做 KV REST 端点（宿主接线归 transport 后续）。
