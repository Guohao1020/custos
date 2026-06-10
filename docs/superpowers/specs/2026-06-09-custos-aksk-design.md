# Custos AK·SK secrets engine + 轮换 设计规格

> **类型**：生产架构路线图子项目 **M09 / P-AKSK**（v0.2）设计。SecretsEngine SPI 的第二实现：动态 AK·SK 凭证 + 轮换。
> **校订**：2026-06-09 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec `2026-06-09-custos-production-architecture-spec.md` §3/§7；经纪层设计 `docs/design/06-secrets-broker.md`。

---

## 1. 目标与范围

为 SecretsEngine SPI 提供第二实现，证明引擎可扩展性：动态签发短时 **AK·SK（云访问密钥对）**、TTL 到期自动撤销、支持**多版本过渡轮换**。

- **纳入**：`AkSkPair`、`AkSkProvider` SPI + `InMemoryAkSkProvider`、`AkSkSecretsEngine`（issue/revoke/rotate）。复用 `LeaseManager`（TTL/撤销）+ `IssuedCred` + `SecretsEngineRegistry`。纯 Java、可单测。
- **非目标（留后续）**：真实 AWS STS / 阿里云 RAM 对接（作 `AkSkProvider` 未来实现）；AK·SK 权限策略下发（云侧 IAM）；跨区域。
- **决策（已定）**：**内存模拟 Provider + SPI**；**复用 engine LeaseManager**；**轮换支持 grace 多版本过渡**。
- **secretless 红线**：SK 仅在 `issue/rotate` 返回值出现，绝不进 LLM/日志（与经纪层一致）。

---

## 2. 架构与数据流

```
AkSkSecretsEngine implements SecretsEngine（type="ak-sk"）
  issue(path, ttl):
     AkSkPair p = provider.mint(path)                         // 现场铸 (accessKeyId, secretKey)
     Lease lease = leases.register("aksk/"+path, ttl,
                                   l -> provider.revoke(p.accessKeyId()))   // 到期/撤销→吊销 AK
     return new IssuedCred(p.accessKeyId(), p.secretKey(), lease.leaseId(), lease.expireAt())
  revoke(leaseId):
     leases.revoke(leaseId)                                    // 触发 Revoker → provider.revoke(akId)
  rotate(oldLeaseId, path, newTtl, grace):
     IssuedCred fresh = issue(path, newTtl)                    // 新一份
     grace.isZero() ? leases.revoke(oldLeaseId)                // 硬轮换：立即撤销旧
                    : leases.renew(oldLeaseId, grace)          // 多版本过渡：旧存活 grace 后后台扫描自动撤销
     return fresh
```
依赖：`AkSkSecretsEngine` 依赖 `AkSkProvider` + `LeaseManager` 接口；不依赖云 SDK。

---

## 3. 组件与契约

`engine/src/main/java/io/custos/engine/secrets/`：

```java
/** 云访问密钥对。 */
public record AkSkPair(String accessKeyId, String secretKey) {}

/** AK·SK 后端 SPI：现场铸/吊销。未来加 AwsStsProvider / AliyunRamProvider。 */
public interface AkSkProvider {
    AkSkPair mint(String mount);
    void revoke(String accessKeyId);
}

/** 内存模拟 Provider（计划后续接真实云只换实现）。 */
public final class InMemoryAkSkProvider implements AkSkProvider {
    public AkSkPair mint(String mount);           // AK="AKIA"+12hex, SK=32hex；记入活跃集
    public void revoke(String accessKeyId);        // 从活跃集移除
    public boolean isActive(String accessKeyId);   // 供测试断言
}

/** AK·SK 动态凭证引擎（SecretsEngine 第二实现）。 */
public final class AkSkSecretsEngine implements SecretsEngine {
    public AkSkSecretsEngine(AkSkProvider provider, LeaseManager leases);
    public String type();                          // "ak-sk"
    public IssuedCred issue(String path, Duration ttl);
    public void revoke(String leaseId);
    /** 轮换：发新一份；grace=0 立即撤旧，grace>0 旧续到 grace 后自动撤。返回新凭证。 */
    public IssuedCred rotate(String oldLeaseId, String path, Duration newTtl, Duration grace);
}
```
> `IssuedCred(username=accessKeyId, password=secretKey, leaseId, expireAt)`——复用既有记录，AK 当 username、SK 当 password，与 SecretsEngine SPI 契约一致。

---

## 4. 轮换语义（多版本过渡）

- `grace > 0`：发新凭证；`leases.renew(oldLeaseId, grace)` 把旧租约到期改为 `now+grace`。旧 AK 在 grace 窗口内仍有效（消费方平滑切换），窗口后由 `DefaultLeaseManager` 后台扫描触发旧租约的 Revoker → `provider.revoke(oldAkId)`。
- `grace == 0`：硬轮换，`leases.revoke(oldLeaseId)` 立即吊销旧 AK。
- 新旧 leaseId 不同；轮换不复用旧 AK。

---

## 5. 错误处理

| 场景 | 处理 |
|---|---|
| provider.mint 失败 | 抛 IllegalStateException（内存实现不抛；真实云实现自负） |
| revoke 未知 leaseId | LeaseManager.revoke 对未知 id 安全返回（不抛） |
| rotate 的 oldLeaseId 不存在 | renew/revoke 对未知 id 安全无操作；新凭证照常返回 |

---

## 6. 测试策略（TDD，纯单元，无 Docker）

- `InMemoryAkSkProvider`：mint 产唯一 AK 且 isActive=true；revoke 后 isActive=false。
- `AkSkSecretsEngine.issue`：返回 IssuedCred，accessKeyId 活跃；`revoke(leaseId)` 后该 AK 不活跃。
- `rotate(grace=0)`：旧 AK 立即不活跃、新 AK 活跃、新旧 leaseId 不同。
- `rotate(grace>0)`：旧 AK 仍活跃（窗口内）、新 AK 活跃、旧租约 expireAt 缩短到约 now+grace。
- `SecretsEngineRegistry`：挂到 "aksk" mount 后 `require("aksk").type()=="ak-sk"`。
- 复用 `DefaultLeaseManager`（内存 JSqlClient？）——见下注。

> **租约测试注**：`DefaultLeaseManager` 依赖 `JSqlClient`（MySQL）。为保持 M09 **纯单元无 Docker**，AkSkSecretsEngine 测试用一个**轻量内存 `LeaseManager` 测试替身**（实现 `register/renew/revoke/revokePrefix`，内存 Map），聚焦 Ak·Sk 引擎逻辑；真实 Jimmer 租约的 DB 行为已由 M02 的 `DefaultLeaseManagerIT` 覆盖。引擎生产装配时注入 `DefaultLeaseManager`。

---

## 7. 非目标 / YAGNI

- 不接真实云 STS/RAM（SPI 留缝）。
- 不做云侧 IAM 策略下发、跨区域、AK·SK 权限收窄（超出本增量）。
- 不在 M09 做后台扫描的实时性测试（时间相关）；轮换的确定性效果（立即撤旧 / 旧租约 expireAt 缩短）即测试边界。
