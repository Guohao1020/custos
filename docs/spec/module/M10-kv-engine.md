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
  - id: M10-S1
    title: "实现基于加密存储的版本化键值引擎"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/kv/KvEngine.java"
      - "engine/src/main/java/io/custos/engine/kv/StorageKvEngine.java"
      - "engine/src/test/java/io/custos/engine/kv/StorageKvEngineTest.java"
    docs:
      - "docs/superpowers/specs/2026-06-10-custos-kv-design.md#KvEngine（版本化"
      - "docs/superpowers/plans/2026-06-10-custos-kv.md:29-176"
  - id: M10-S2
    title: "支持 PostgreSQL 的动态只读凭证"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/secrets/PostgresDynamicCredentials.java"
      - "engine/src/test/java/io/custos/engine/secrets/PostgresDynamicCredentialsIT.java"
    docs:
      - "docs/superpowers/specs/2026-06-10-custos-kv-design.md#PostgresDynamicCredentials（SecretsEngine"
      - "docs/superpowers/plans/2026-06-10-custos-kv.md:180-338"
---

# M10 · KV / 更多 secrets engine

静态机密版本化存取（密文性由 Storage/Barrier 继承）+ DB 引擎按方言扩展（MySQL/PG 双实现）。
Oracle/KV-TTL 留 YAGNI。
