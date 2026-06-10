---
id: M11
title: HA · Raft/JRaft 强一致
status: not-started
sprint: v0.3
progress: 0
manualProgress: false
desc: "SOFAJRaft 复制状态机：RaftStorage/RaftSealStore/RaftLeaseManager（leader-only 租约扫描），进程内多节点测试。含 API 核准 gate（Task 0）。spec/plan 已备好。"
docs:
  - { title: "HA Raft 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-ha-raft-design.md" }
  - { title: "HA Raft 实现计划（含 JRaft API 核准 gate）", path: "docs/superpowers/plans/2026-06-10-custos-ha-raft.md" }
  - { title: "引擎加密设计（存储 ADR-3）", path: "docs/design/02-engine-crypto-design.md" }
subtasks:
  - { title: "M11-T0 JRaft API 源码核准 gate（gitee 克隆逐一核准并回写计划）", done: false }
  - { title: "M11-T1 KvOp + 状态机 + Server/Client（单节点往返+快照）", done: false }
  - { title: "M11-T2 3 节点复制 + leader failover 测试", done: false }
  - { title: "M11-T3 RaftStorage/RaftSealStore/RaftLeaseManager（leader-only sweeper）", done: false }
---

# M11 · HA Raft

密文 KV 复制状态机；master/keyring 永不复制——各节点独立分片解封，仅复制 wrapped 配置。
读一致性 MVP 为 leader-local，ReadIndex 列为可选强化。
