---
id: M14
title: 加固（国密实测 · 内存加固 · 压测 · 外部审计）
status: not-started
sprint: v0.4
progress: 0
manualProgress: false
desc: "GmSuite（SM4-GCM/SM3/HmacSM3/SM2，suiteId=0x02，keyLength 适配）+ Zeroize/Keyring.destroy + bench 标签用例与压测 RUNBOOK + AUDIT-PREP 审计准备包。spec/plan 已备好。"
docs:
  - { title: "加固设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-hardening-design.md" }
  - { title: "加固实现计划", path: "docs/superpowers/plans/2026-06-10-custos-hardening.md" }
  - { title: "引擎加密设计（国密/内存安全 §8/§9/§10）", path: "docs/design/02-engine-crypto-design.md" }
subtasks:
  - { title: "M14-T1 GmSuite（BC）+ keyLength 适配 + GmSeal 集成测试", done: false }
  - { title: "M14-T2 Zeroize + Keyring.destroy（内存加固）", done: false }
  - { title: "M14-T3 bench 标签用例 + RUNBOOK + AUDIT-PREP 准备包", done: false }
---

# M14 · 加固

ADR-4 国密落地 + 内存清零系统化 + 压测/审计准备。外部审计本身在外部机构执行；
mlock/JMH 工程化挂账于 AUDIT-PREP/RUNBOOK 按需实施。
