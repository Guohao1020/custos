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
  - { title: "M11-T0 JRaft API 源码核准 gate（gitee 克隆逐一核准并回写计划）", done: true }
  - { title: "M11-T1 KvOp + 状态机 + Server/Client（单节点往返+快照+重启恢复）", done: true }
  - { title: "M11-T2 3 节点复制 + leader failover 测试", done: true }
  - { title: "M11-T3 RaftStorage/RaftSealStore/RaftLeaseManager（leader-only sweeper）", done: true }
---

# M11 · HA Raft

ADR-3 落地：密文 KV 复制状态机；master/keyring 永不复制（各节点独立分片解封）；
租约"不丢不重"（状态机持久 + leader 单扫描者）。读为 leader-local，ReadIndex 留强化。
Java 17+ 需 --add-opens java.base/java.util（已钉入 surefire/failsafe argLine，宿主接线时同样需要）。
