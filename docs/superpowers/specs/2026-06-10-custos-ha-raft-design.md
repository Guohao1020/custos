# Custos HA · Raft/JRaft 强一致 设计规格（M11）

> **类型**：路线图子项目 **M11 / P-HA**（v0.3）设计。存储/租约/解封配置的 Raft 强一致集群化。
> **校订**：2026-06-10 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec §3/§7（ADR-3：MySQL → Raft HA）；`docs/design/02-engine-crypto-design.md` §7。

---

## 1. 目标与范围

单节点 Custos 的三个有状态组件集群化，强一致、不丢不重：
① `RaftStorage implements Storage`（密文 KV 复制状态机）；② `RaftSealStore implements SealStore`（解封配置复制，节点各自内存解封）；③ `RaftLeaseManager implements LeaseManager`（租约为状态机条目，仅 leader 跑到期扫描）。

- **选型**：**SOFAJRaft**（`com.alipay.sofa:jraft-core`，Apache-2.0、国产、纯 JVM 可进程内多节点测试）——符合自主可控约束。
- **非目标**：跨数据中心、动态扩缩容运维工具、Learner 只读副本、Multi-Raft 分片（单 group 起步）。

## 2. 架构

```
Storage/SealStore/LeaseManager 调用
        ▼
RaftKvClient（leader 写：put/delete Op 序列化为 Task；读：leader ReadIndex / MVP 先 leader-local）
        ▼ JRaft 复制
RaftKvStateMachine（onApply：维护内存 Map<String,byte[]>；onSnapshotSave/Load：全量快照）
```
- **加密边界不变**：状态机存的是 **Barrier 密文**（调用方仍先 seal 再 put），Raft 层不接触明文与密钥。
- **解封协调**：master/keyring 永不复制、永不落盘——每个节点独立用分片解封（操作员对每节点 unseal，或后续 KMS 自动解封）；`RaftSealStore` 只复制 `wrapped_barrier/threshold/shares` 密文配置，使任意节点可加入并解封。
- **租约**：租约行进状态机；到期扫描只在 leader 执行（`Node.isLeader()` 守卫），failover 后新 leader 接管扫描——保证"不重"（单扫描者）+"不丢"（状态机持久）。

## 3. 接口契约

```java
public final class RaftKvStateMachine extends StateMachineAdapter { /* onApply/onSnapshotSave/onSnapshotLoad */ }
public final class RaftKvServer { /* 起一个 RaftGroupService 节点：groupId, serverId, initialConf, dataPath */ }
public final class RaftKvClient { byte[] get(String k); void put(String k, byte[] v); void delete(String k); java.util.List<String> list(String prefix); }
public final class RaftStorage implements io.custos.engine.storage.Storage { /* 委托 RaftKvClient + Barrier 在调用方 */ }
public final class RaftSealStore implements io.custos.engine.seal.SealStore { /* get/put 经 RaftKvClient */ }
public final class RaftLeaseManager implements io.custos.engine.lease.LeaseManager { /* 租约条目 + leader-only sweeper */ }
```
> 上述签名为契约级；JRaft 具体 API（`Node`/`Task`/`Closure`/`RouteTable`/CliService）**必须先源码核准**（见 plan Task 0）再实现——本项目铁律：未经核准的第三方 API 不写实现。

## 4. 测试策略（纯 JVM，无 Docker）

JRaft 支持进程内多节点（不同端口 + 本地数据目录）：
- 单节点 group：put→get 往返、快照保存/加载。
- 3 节点：leader 写入 → follower 状态机可见（apply 后）；kill leader → 新 leader 当选 → 继续读写（failover）。
- `RaftLeaseManager`：leader-only 扫描（非 leader 不扫）、failover 后新 leader 接管。
- 临时数据目录用 JUnit `@TempDir`。

## 5. 风险

| 风险 | 应对 |
|---|---|
| JRaft API 记忆失真 | plan Task 0 先 gitee 克隆核准（gitee.com/sofastack/sofa-jraft），逐一修正计划代码再开工 |
| 进程内 3 节点端口冲突 | 测试动态选可用端口 |
| 读一致性 | MVP leader-local 读 + 注明；ReadIndex 列入计划可选步 |

## 6. YAGNI

单 Raft group；不做分片/迁移；不做跨机房；CLI 集群运维命令留 M13 之后。
