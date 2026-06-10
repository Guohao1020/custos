# Custos 加固（国密实测 · 内存加固 · 压测 · 外部审计）设计规格（M14）

> **类型**：路线图子项目 **M14 / P-Hardening**（v0.4）设计。生产条件收口。
> **校订**：2026-06-10 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：`docs/design/02-engine-crypto-design.md` §8（内存安全）/§9/§10（国密）；总体架构 spec §5/§8（v0.4=外部审计通过）。

---

## 1. 目标与范围

四件事，三件可代码交付、一件是流程：
① **GmSuite**——国密套件真实现（ADR-4 落地）；② **内存加固**——密钥材料清零的系统化（Zeroize + 审计现有路径）；③ **压测**——可重复的吞吐 smoke + 完整压测 runbook；④ **外部审计**——审计准备包（流程产物，审计本身在外部）。

## 2. GmSuite（CipherSuite 第二实现，suiteId=0x02）

经 **BouncyCastle**（bcprov-jdk18on 1.78.1，经审计、绝不自写算法）：

| CipherSuite 方法 | 国密算法（BC JCE 名） |
|---|---|
| encrypt/decrypt | `SM4/GCM/NoPadding`（key **16 字节**、nonce 12、tag 128 位，封套格式同 Intl） |
| hash | `SM3` |
| hmac | `HmacSM3` |
| genSignKey/sign/verify | EC `sm2p256v1` 曲线 + `SM3withSM2` |

**关键适配**：SM4 密钥 16 字节而 AES-256 是 32——`CipherSuite` 增 `default int keyLength() { return 32; }`，GmSuite 覆写返回 16；`DefaultSealManager.init` 的 master/barrier key 长度改用 `suite.keyLength()`（兼容：Intl 行为不变）。封套 `suite_id` 字节已预留 0x02，多套件并存解密按头路由（既有 Barrier 设计）。

测试：GCM 往返/tag 篡改拒/SM3 长度与确定性/HmacSM3/SM2 签验+错钥拒；以及 **GmSuite 跑通完整 seal/unseal 链**（DefaultSealManager+GmSuite 的 init→unseal→barrier 往返）。

## 3. 内存加固

- `Zeroize` 工具（`io.custos.engine.crypto`）：`wipe(byte[]...)` 集中清零；替换散落的 `Arrays.fill`。
- 审计并补齐清零点：`DefaultSealManager`（已有 init/seal 清零，补 combine 中间值）、`Keyring`（add 后入参副本由调用方清；keyring 销毁时 wipe 全部版本）、`OperatorService.seal()` 路径。
- 文档化 JVM 级缓解（`docs/design/02` §8 已有蓝图）：禁 swap/core dump、堆外/mlock 属部署项——写进 AUDIT-PREP 检查单，不在本增量实现 JNA mlock（平台相关，审计前按需）。
- 测试：`Zeroize.wipe` 置零断言；`Keyring.destroy()` 后 `key()` 抛 IllegalState。

## 4. 压测

- **吞吐 smoke**：JUnit `@Tag("bench")`（默认 build 排除，`-Dgroups=bench` 才跑）——seal/open、issue/revoke 的简单吞吐与延迟打印，防性能悬崖回归。
- **完整压测 runbook**：`docs/bench/RUNBOOK.md`——JMH 基准项清单（barrier 加解密、JWT 签验、PDP decide、端到端 query_db）、目标参考值、环境要求；JMH 工程化留审计前执行。

## 5. 外部审计准备（流程产物）

`docs/audit/AUDIT-PREP.md`：资产与信任边界图引用（02 §3 STRIDE）、密钥生命周期表、依赖与许可清单（00 §7）、已知缺口清单（如 mlock 未实现、admin 面 TLS 部署项）、测试覆盖摘要（各模块用例数）、负路径清单。审计本身（外部机构）不在仓库范围。

## 6. 测试策略

GmSuite 全 TDD（约 8-10 用例，含与 SealManager 集成）；Zeroize/Keyring.destroy 单测；bench 标签用例不进默认门禁。

## 7. YAGNI

不做 Tongsuo/硬件加密机对接、不做 JNA mlock（检查单挂账）、不做自动化渗透；JMH 工程留 runbook 指引按需建。
