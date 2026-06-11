# Custos 外部安全审计准备包（AUDIT-PREP）

> 对应 M14 加固 spec §5、总体架构 spec §8（v0.4 = 外部审计通过即具备生产条件）。审计本身由外部机构执行；本文是给审计方的入口包 + 我方已知缺口的诚实清单。

## 1. 资产与信任边界

- 威胁模型（STRIDE）与信任边界图：`docs/design/02-engine-crypto-design.md` §3。
- 最高价值资产：master key / 审计链完整性 / "密钥不进 LLM" 红线。
- 边界铁律（代码层）：`engine` 无 Spring/MCP/Nacos 依赖；`authz` 不持密钥；`broker` 不判策略；Nacos 绝不存密钥；Raft 状态机只见 Barrier 密文。

## 2. 密钥生命周期表

| 密钥 | 产生 | 存储 | 使用 | 销毁 |
|---|---|---|---|---|
| unseal/master | `DefaultSealManager.init`（SecureRandom，长度=suite.keyLength()） | 仅 Shamir 分片在操作员手中；不落盘不进 env | 解封瞬间重建 | init/unseal 后立即 `Zeroize.wipe` |
| barrier keyring | init 时生成 | 密文（master 加密）落 SealStore（MySQL/Raft） | 解封后内存 `Keyring` | `seal()` → `Keyring.destroy()` 清零 |
| 审计 HMAC key | keyring 活动密钥确定性派生（HMAC, "custos-audit-key"） | 不落盘 | `HashChainAuditLog` | 随 keyring 销毁 |
| JWT 签名钥 | host 启动生成（ECDSA P-256）；生产应由 KeyManager/Barrier 托管（缺口 G3） | 内存 | `JwtTokenService` | 进程退出 |
| SVID CA 私钥 | `X509SvidIssuer` 构造（P-256） | 内存（缺口 G3 同） | 签发 SVID | 进程退出 |
| 动态 DB 凭证 / AK·SK | 现场生成（hex/AKIA+hex） | 不持久化；仅 IssuedCred 返回值 | broker 即用即焚 | 租约撤销→DROP/吊销 |

## 3. 依赖与许可

全表：`docs/design/00-synthesis.md` §7。要点：全部 Apache/MIT/BSD 兼容（jjwt/jCasbin/Nacos/Jimmer/JRaft/BouncyCastle/picocli/Spring Boot）；**无** Vault(BSL)/OpenBao(MPL)/Infisical-EE 代码混入；Custos 自身 Apache-2.0。

## 4. 测试覆盖摘要（审计时以 `mvn -B verify` 实测为准）

| 模块 | 单测 | IT | 关键负路径 |
|---|---|---|---|
| engine | 45+（crypto/seal/raft/kv/secrets，含 GmSuite/Zeroize） | 14（MySQL/PG Testcontainers） | GCM tag 篡改拒、缺片不可解封、审计链断链定位、越权写拒、撤销后拒登录 |
| identity | 18 | — | 过期/换钥/吊销拒、异 CA SVID 拒、空交集最小权限 |
| authz | 15 | —（Nacos 冒烟环境门控） | 默认拒、deny 优先、跨租户隔离、越密级拒、高危 REQUIRE_APPROVAL |
| broker/app | — | 7（含宿主端到端） | 未解封 409、结果无凭证泄漏断言、非 SELECT/多语句拒 |
| sdk/cli | 7 | — | backoff、请求形状 |

## 5. 已知缺口（诚实清单，审计重点）

| # | 缺口 | 现状 | 缓解/计划 |
|---|---|---|---|
| G1 | JNA mlock / 禁 swap·core dump 未实现 | 文档级（02 §8） | 部署项：容器 `--memory-swap`、systemd 限制；JNA mlock 按审计意见实施 |
| G2 | admin 面 TLS | 仅 Bearer Token + 回环约定 | 生产部署置于 mTLS/网关后；starter 支持 https base-url |
| G3 | JWT 签名钥/SVID CA 钥未经 Barrier 托管 | host 启动内存生成 | KeyManager 接线（路线图）；私钥不落盘已满足 |
| G4 | Raft 读为 leader-local | 弱于线性化读 | ReadIndex 强化列入计划可选步 |
| G5 | KV 单写者假设 | 宿主层串行化约定 | Raft 线性化写已就绪，CAS 语义按需加 |
| G6 | stdio MCP 模式假定启动前已解封 | 文档声明 | REST 模式无此约束（默认） |
| G7 | 资源高权限管理凭证权限过大 | demo 用 `GRANT ALL` 的 custos 账号作资源 admin | **密钥已 Barrier 加密托管✓（v0.5 资源接入，落盘无明文、用后 Zeroize、可 rotate-admin 轮换）**；剩余缺口=权限过大，生产应为每资源配最小权限 admin 角色（仅 `CREATE USER`/`GRANT`/`DROP USER`） |

## 6. 审计建议入口

1. 跑通 `examples/demo.md`（AC1–AC8）建立行为基线；
2. 重点 fuzz：Barrier 封套解析、KvOp/租约条目解码、策略 CSV 解析、SVID PEM 解析；
3. 检查点对照 §2 密钥表逐项验证"不落盘/清零/不出边界"。
