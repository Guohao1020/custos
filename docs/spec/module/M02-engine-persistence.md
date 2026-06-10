---
id: M02
title: 引擎持久化（Jimmer · MySQL 全密文）
status: done
sprint: v0.1
progress: 100
manualProgress: false
desc: "Jimmer 全密文存储 · SealStore · 哈希链防篡改审计 · 租约 · 动态 MySQL 只读凭证。12 IT 全绿。"
docs:
  - { title: "实现计划 2/5 · 引擎持久化", path: "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md" }
  - { title: "Jimmer 研究笔记", path: "docs/research/jimmer.md" }
subtasks:
  - id: M02-S1
    title: "接入 Jimmer 实现全密文键值存储"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/storage/StorageEntry.java"
      - "engine/src/main/java/io/custos/engine/storage/JimmerStorage.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.4"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md#Task 1"
  - id: M02-S2
    title: "将密封配置持久化到数据库"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/seal/SealConfigEntry.java"
      - "engine/src/main/java/io/custos/engine/seal/JimmerSealStore.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md:398-538"
  - id: M02-S3
    title: "实现防篡改的哈希链审计日志"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/audit/AuditRow.java"
      - "engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.6"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md#Task 3"
  - id: M02-S4
    title: "实现凭证租约的登记续期与撤销"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/lease/LeaseRow.java"
      - "engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.5"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md#Task 4"
  - id: M02-S5
    title: "现场签发即用即焚的数据库只读凭证"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/secrets/DynamicDbCredentials.java"
      - "engine/src/main/java/io/custos/engine/secrets/IssuedCred.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md#Task 5"
---

# M02 · 引擎持久化

Jimmer 不可变实体 + JSqlClient，值列存 Barrier 密文，加解密在 service 层；裸 JDBC 仅用于目标库账号 DDL。
