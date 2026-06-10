---
id: M01
title: 引擎基座（crypto · barrier · shamir · seal）
status: done
sprint: v0.1
progress: 100
manualProgress: false
desc: "CipherSuite/AES-256-GCM/SHA/HMAC/ECDSA · Barrier 封套 · Shamir 解封 · 密钥层级。18 单测全绿。"
docs:
  - { title: "引擎加密设计（重中之重）", path: "docs/design/02-engine-crypto-design.md" }
  - { title: "实现计划 1/5 · 引擎基座", path: "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md" }
subtasks:
  - { title: "T1 Maven 多模块脚手架 + CI + 冒烟", done: true }
  - { title: "T2 IntlSuite AES-256-GCM（TDD）", done: true }
  - { title: "T3 SHA-256 + HMAC", done: true }
  - { title: "T4 ECDSA P-256 签名/验签", done: true }
  - { title: "T5 Keyring + Barrier 封套", done: true }
  - { title: "T6 Shamir 分片封装", done: true }
  - { title: "T7 密钥层级 + Seal/Unseal", done: true }
---

# M01 · 引擎基座

自研密码内核：可切换 CipherSuite（国际套件落地，国密预留）、Barrier 信封加密、Shamir 解封、密钥层级。
