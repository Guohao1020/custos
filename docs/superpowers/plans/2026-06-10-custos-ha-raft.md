# Custos HA · Raft/JRaft（M11）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 SOFAJRaft 把 Storage/SealStore/LeaseManager 集群化（复制状态机 + leader-only 租约扫描），进程内多节点测试验证复制与 failover。

**Architecture:** `RaftKvStateMachine`（内存 Map + 快照）承载密文 KV；`RaftKvClient` 走 leader 提交写 Task、读取走 leader-local（ReadIndex 列为可选强化）；`RaftStorage/RaftSealStore/RaftLeaseManager` 三个既有 SPI 落到该 KV 上。加密边界不变：状态机只见 Barrier 密文。

**Tech Stack:** Java 21 · com.alipay.sofa:jraft-core 1.3.14 · JUnit 5（进程内多节点，无 Docker）

> 前置：M02（Storage/SealStore/LeaseManager SPI）。对应 spec `docs/superpowers/specs/2026-06-10-custos-ha-raft-design.md`。
> **铁律 gate**：Task 0 未完成（JRaft API 逐一源码核准并修正本计划代码）前，禁止进入 Task 1+。本计划中 JRaft 相关代码段是**契约草图**，以核准结果为准——这是本项目从 jjwt/jCasbin/MCP 核准流程沿袭的规矩。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/pom.xml` | 加 jraft-core 1.3.14 |
| `engine/src/main/java/io/custos/engine/raft/KvOp.java` | put/delete 操作（序列化进 raft log）|
| `engine/src/main/java/io/custos/engine/raft/RaftKvStateMachine.java` | 状态机（apply/snapshot）|
| `engine/src/main/java/io/custos/engine/raft/RaftKvServer.java` | 起一个 raft 节点 |
| `engine/src/main/java/io/custos/engine/raft/RaftKvClient.java` | get/put/delete/list |
| `engine/src/main/java/io/custos/engine/raft/RaftStorage.java` | Storage SPI 落地 |
| `engine/src/main/java/io/custos/engine/raft/RaftSealStore.java` | SealStore SPI 落地 |
| `engine/src/main/java/io/custos/engine/raft/RaftLeaseManager.java` | LeaseManager（leader-only 扫描）|
| `engine/src/test/java/io/custos/engine/raft/*` | 单节点/3 节点/failover/租约测试 |

---

## Task 0: JRaft API 源码核准 gate（必须先做）

- [ ] **Step 1: 克隆并核准 API**

```bash
git clone --depth 1 https://gitee.com/sofastack/sofa-jraft research/sofa-jraft
```
逐一核准并把结果写回本计划（修正后续代码段）：
1. `RaftGroupService(groupId, PeerId, NodeOptions)` 构造与 `start()` 返回 `Node` 的签名；
2. `NodeOptions`：`setFsm/setLogUri/setRaftMetaUri/setSnapshotUri/setInitialConf` 的当前名称；
3. `StateMachineAdapter.onApply(Iterator)` 中 `iter.getData()`（ByteBuffer）与 `iter.done()`/`done.run(Status)` 约定；
4. 快照：`onSnapshotSave(SnapshotWriter, Closure)` / `onSnapshotLoad(SnapshotReader)` 的文件写入/注册（`writer.addFile`）；
5. 客户端路由：`RouteTable.getInstance().updateConfiguration/refreshLeader/selectLeader` + `CliClientServiceImpl`；或进程内直接持 `Node` 引用提交 `Task`（测试态最简路径——**优先核准此路径**）；
6. `Task(data, done)` 提交：`node.apply(task)`；
7. leader 判定：`node.isLeader()`；
8. 多节点进程内组网：同 JVM 多 `RaftGroupService` 不同端口/数据目录是否受支持（官方 test 用法）。

- [x] **Step 2: 把核准差异回写本计划（Edit 本文件），然后才进 Task 1**

> **核准结果（2026-06-10，gitee.com/sofastack/sofa-jraft@master）**：
> - 版本：**用 1.4.0**（Central 在档，dependency:get 验证通过；计划原写 1.3.14 作废）。
> - `RaftGroupService(String groupId, PeerId serverId, NodeOptions)` + `start()` → `Node` ✓
> - `NodeOptions`：`setFsm/setLogUri/setRaftMetaUri/setSnapshotUri/setInitialConf(Configuration)/setElectionTimeoutMs` ✓
> - `Node.apply(Task)`、`Node.isLeader()` ✓；`Task(ByteBuffer data, Closure done)` ✓；`Closure.run(Status)` ✓
> - `StateMachine.onSnapshotSave(SnapshotWriter, Closure)` / `onSnapshotLoad(SnapshotReader): boolean`；`SnapshotWriter.addFile(String, Message)`；`onLeaderStart(long term)`（leader-only sweeper 钩子）✓
> - `JRaftUtils.getConfiguration("ip:port,...")` / `getPeerId("ip:port")` ✓
> - `Iterator extends java.util.Iterator<ByteBuffer>`，`getData()/done()` ✓
> - 进程内多节点：jraft-core 自测即此用法，不同端口 + 各自数据目录即可。

- [ ] **Step 3: 提交计划修正**
```bash
git add docs/superpowers/plans/2026-06-10-custos-ha-raft.md
git commit -m "docs(plan): verify JRaft API against source and correct M11 plan"
```

---

## Task 1: KvOp + RaftKvStateMachine + RaftKvServer/Client（单节点往返）

**Files:** Create 上表 raft 包前 5 个文件；Test `RaftKvSingleNodeTest.java`

- [ ] **Step 1: 写失败测试（单节点 put→get 往返 + 重启快照恢复）**

```java
// 契约草图（以 Task 0 核准修正为准）
@Test void singleNodePutGetRoundTrips(@TempDir Path dir) {
    RaftKvServer server = RaftKvServer.start("custos-kv", "127.0.0.1:18091", "127.0.0.1:18091", dir);
    RaftKvClient client = server.localClient();          // 进程内直连 Node
    awaitLeader(server);                                  // 轮询 node.isLeader()
    client.put("k1", "v1".getBytes());
    assertArrayEquals("v1".getBytes(), client.get("k1"));
    client.delete("k1");
    assertNull(client.get("k1"));
    server.shutdown();
}
```

- [ ] **Step 2: 实现 KvOp（type+key+value 的简单二进制序列化，避免引第三方序列化库）**
- [ ] **Step 3: 实现 StateMachine（onApply 维护 `ConcurrentHashMap`；快照=全量 map 序列化到文件）**
- [ ] **Step 4: 实现 Server/Client（写经 `node.apply(Task)` 同步等 done；读 leader-local 从状态机 map）**
- [ ] **Step 5: 跑测试绿 → 提交** `feat(engine): JRaft single-node replicated KV (state machine + snapshot)`

---

## Task 2: 3 节点复制 + failover 测试

**Files:** Test `RaftKvClusterTest.java`

- [ ] **Step 1: 写失败测试**

```java
// 契约草图：三节点 127.0.0.1:18091/18092/18093，initialConf 互联
@Test void replicatesToFollowersAndSurvivesLeaderFailover(@TempDir Path dir) {
    List<RaftKvServer> nodes = RaftKvServer.startCluster("custos-kv", PEERS, dir);
    RaftKvServer leader = awaitLeaderOf(nodes);
    leader.localClient().put("k", "v".getBytes());
    awaitApplied(nodes, "k");                                  // 三个状态机都可见
    leader.shutdown();                                          // kill leader
    RaftKvServer newLeader = awaitLeaderOf(remaining(nodes));   // 新 leader 当选
    assertArrayEquals("v".getBytes(), newLeader.localClient().get("k"));
    newLeader.localClient().put("k2", "v2".getBytes());         // 继续可写
}
```

- [ ] **Step 2: 实现 startCluster + 测试工具（动态端口探测避免冲突）→ 绿 → 提交** `test(engine): 3-node raft replication and leader failover`

---

## Task 3: RaftStorage + RaftSealStore + RaftLeaseManager

**Files:** Create 三个 SPI 落地类；Test `RaftSpiAdaptersTest.java`

- [ ] **Step 1: 写失败测试**
  - `RaftStorage`：put/get/delete/list（前缀）契约（密文由调用方 seal，测试只验字节往返）；
  - `RaftSealStore`：`DefaultSealManager` + `RaftSealStore` 跨"节点实例"恢复解封（同 M02 JimmerSealStoreIT 形态，store 换 raft）；
  - `RaftLeaseManager`：register/renew/revoke/revokePrefix 语义同 `DefaultLeaseManager`；**leader-only 扫描**——非 leader 节点不触发 Revoker，leader shutdown 后新 leader 接管到期撤销。
- [ ] **Step 2: 实现三类（租约行编码进 KV：`lease/{id}` → resourcePath|issuedAt|expireAt|revoked；扫描线程 `node.isLeader()` 守卫）**
- [ ] **Step 3: 绿 → `mvn -B verify` 全量回归 → 提交** `feat(engine): raft-backed Storage/SealStore/LeaseManager (leader-only sweeper)`

---

## Self-Review（对照 HA spec）

- 覆盖：状态机/Server/Client(§2/§3)→Task1；复制+failover(§4)→Task2；三 SPI + leader-only 租约(§2/§3)→Task3；API 核准风险(§5)→Task 0 gate。
- 诚实声明：JRaft 代码段为契约草图，Task 0 核准后回写修正——与本项目 jjwt/jCasbin/MCP 的"先核准后实现"流程一致。
- 读一致性 MVP 为 leader-local（spec §5 注明），ReadIndex 为可选强化步。
