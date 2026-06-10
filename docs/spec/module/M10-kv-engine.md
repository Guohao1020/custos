---
id: M10
title: KV / 更多 secrets engine
status: done
sprint: v0.3
progress: 100
manualProgress: false
desc: "版本化 KvEngine（落 Storage 永远密文，meta/#v{n} 键布局）+ PostgresDynamicCredentials（SecretsEngine 第三实现，CREATE ROLE/GRANT SELECT/DROP ROLE）。KV 4 单测 + PG IT 2 用例全绿。"
docs:
  - { title: "KV 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-kv-design.md" }
  - { title: "KV 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-kv.md" }
  - { title: "经纪层设计", path: "docs/design/06-secrets-broker.md" }
subtasks:
  - { title: "M10-T1 KvEngine 接口 + StorageKvEngine（版本化，纯单元）", done: true }
  - { title: "M10-T2 PostgresDynamicCredentials（Testcontainers PG IT）", done: true }
---

# M10 · KV / 更多 secrets engine

静态机密版本化存取（密文性由 Storage/Barrier 继承）+ DB 引擎按方言扩展（MySQL/PG 双实现）。
Oracle/KV-TTL 留 YAGNI。
