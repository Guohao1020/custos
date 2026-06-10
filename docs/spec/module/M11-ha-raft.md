---
id: M11
title: HA · Raft/JRaft 强一致
status: done
sprint: v0.3
progress: 100
manualProgress: false
desc: "SOFAJRaft 1.4.0（源码核准）复制状态机：RaftStorage/RaftSealStore/RaftLeaseManager（leader-only 扫描）。单节点快照重启恢复 + 3 节点复制/failover + SPI 适配 6 测试全绿。"
docs:
  - { title: "HA Raft 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-ha-raft-design.md" }
  - { title: "HA Raft 实现计划（含 Task 0 核准结果）", path: "docs/superpowers/plans/2026-06-10-custos-ha-raft.md" }
  - { title: "引擎加密设计（存储 ADR-3）", path: "docs/design/02-engine-crypto-design.md" }
subtasks:
  - id: M11-S0
    title: "逐一核准 JRaft 第三方 API 并回写计划"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/raft/RaftKvServer.java"
      - "engine/src/main/java/io/custos/engine/raft/RaftKvStateMachine.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-ha-raft.md#Task 0"
  - id: M11-S1
    title: "实现单节点复制状态机含快照与重启恢复"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/raft/KvOp.java"
      - "engine/src/main/java/io/custos/engine/raft/RaftKvStateMachine.java"
      - "engine/src/main/java/io/custos/engine/raft/RaftKvServer.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-ha-raft.md:69-92"
  - id: M11-S2
    title: "验证三节点复制与主节点故障转移"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/raft/RaftKvServer.java:48-52"
      - "engine/src/test/java/io/custos/engine/raft/RaftKvClusterTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-ha-raft.md:96-117"
  - id: M11-S3
    title: "把存储密封租约适配到 Raft 复制层"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/raft/RaftStorage.java"
      - "engine/src/main/java/io/custos/engine/raft/RaftSealStore.java"
      - "engine/src/main/java/io/custos/engine/raft/RaftLeaseManager.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-ha-raft.md:120-130"
      - "docs/superpowers/specs/2026-06-10-custos-ha-raft-design.md:2-29"
---

# M11 · HA Raft

ADR-3 落地：密文 KV 复制状态机；master/keyring 永不复制（各节点独立分片解封）；
租约"不丢不重"（状态机持久 + leader 单扫描者）。读为 leader-local，ReadIndex 留强化。
Java 17+ 需 --add-opens java.base/java.util（已钉入 surefire/failsafe argLine，宿主接线时同样需要）。
