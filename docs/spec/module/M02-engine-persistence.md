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
  - { title: "P2-T1 Jimmer 接入 + StorageEntry + JimmerStorage（IT）", done: true }
  - { title: "P2-T2 SealConfigEntry + JimmerSealStore（IT）", done: true }
  - { title: "P2-T3 哈希链审计 HashChainAuditLog（IT）", done: true }
  - { title: "P2-T4 租约 DefaultLeaseManager（IT）", done: true }
  - { title: "P2-T5 动态 DB 凭证 DynamicDbCredentials（IT）", done: true }
---

# M02 · 引擎持久化

Jimmer 不可变实体 + JSqlClient，值列存 Barrier 密文，加解密在 service 层；裸 JDBC 仅用于目标库账号 DDL。
