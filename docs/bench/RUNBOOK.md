# Custos 压测 Runbook

> 对应 M14 加固 spec §4。两层：① 吞吐 smoke（已实现，防性能悬崖回归）；② 完整 JMH 基准（外部审计前按本清单工程化）。

## 1. 吞吐 smoke（现在就能跑）

bench 标签用例默认排除（不进 CI 门禁），显式跑：

```bash
mvn -pl engine test -DbenchExcluded= -Dgroups=bench
```

当前覆盖：
| 用例 | 内容 | 输出 |
|---|---|---|
| `BarrierBenchSmokeTest.sealOpenThroughput1KiB` | Barrier seal+open 1KiB ×50k（5k 预热） | ops/s · p50 · p99（打印，不设硬阈值） |

判读：跑两个版本对比打印值，量级下滑（如 >30%）即性能悬崖，回 git bisect。

## 2. 完整 JMH 基准（审计前工程化清单）

建独立 `bench` 模块（jmh-core + jmh-generator-annprocess，不进默认 reactor profile），基准项：

| 基准 | 度量 | 参考目标（首轮实测后填入） |
|---|---|---|
| `BarrierBench` | seal/open 1KiB/64KiB 吞吐与分位延迟 | — |
| `JwtBench` | issue/verify（ES256）ops/s | — |
| `PdpBench` | `AbacPdp.decide`（RBAC+风险链路）ops/s | — |
| `QueryDbE2EBench` | host `/query_db` 端到端 P50/P99（含动态凭证签发+撤销） | — |
| `GmVsIntlBench` | GmSuite vs IntlSuite 同载荷对比 | — |

环境要求：固定 CPU 频率（关 turbo）、JDK 21、`-Xms=-Xmx`、JMH fork≥2 / warmup≥5 / iteration≥5；DB 类基准用本机 MySQL/PG 容器，记录容器版本。

## 3. 注意

- bench 用例与 JMH 结果都**不**作为 CI 门禁（环境噪声大），只做趋势记录。
- Raft 相关基准（复制写延迟）待 ReadIndex 强化后一并做，避免给 leader-local 读测出虚高。
