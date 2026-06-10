---
id: M10
title: KV / 更多 secrets engine
status: not-started
sprint: v0.3
progress: 0
manualProgress: false
desc: "版本化 KV 引擎（落 Storage、永远密文）+ PostgreSQL 动态只读凭证（SecretsEngine 第三实现）。spec/plan 已备好可开工。"
docs:
  - { title: "KV 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-kv-design.md" }
  - { title: "KV 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-kv.md" }
  - { title: "经纪层设计", path: "docs/design/06-secrets-broker.md" }
subtasks:
  - { title: "M10-T1 KvEngine 接口 + StorageKvEngine（版本化，纯单元）", done: false }
  - { title: "M10-T2 PostgresDynamicCredentials（Testcontainers PG IT）", done: false }
---

# M10 · KV / 更多 secrets engine

版本化 KV（kv/{path}#meta + #v{n} 键布局，密文性由 Storage/Barrier 继承）+ PG 动态凭证镜像 MySQL 实现。
Oracle/KV-TTL 为非目标（YAGNI）。
