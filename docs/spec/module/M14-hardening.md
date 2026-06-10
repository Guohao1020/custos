---
id: M14
title: 加固（国密实测 · 内存加固 · 压测 · 外部审计）
status: done
sprint: v0.4
progress: 100
manualProgress: false
desc: "GmSuite（SM4-GCM/SM3/HmacSM3/SM2，suiteId=0x02，keyLength 适配，国密跑通完整 seal 链）+ Zeroize/Keyring.destroy + bench 基线（Barrier 1KiB ≈13万 ops/s, p99=34µs）+ RUNBOOK + AUDIT-PREP（密钥生命周期表 + 缺口 G1-G6）。engine 45 单测全绿。"
docs:
  - { title: "加固设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-hardening-design.md" }
  - { title: "加固实现计划", path: "docs/superpowers/plans/2026-06-10-custos-hardening.md" }
  - { title: "压测 RUNBOOK", path: "docs/bench/RUNBOOK.md" }
  - { title: "审计准备包 AUDIT-PREP", path: "docs/audit/AUDIT-PREP.md" }
subtasks:
  - { title: "M14-T1 GmSuite（BC）+ keyLength 适配 + GmSeal 集成测试", done: true }
  - { title: "M14-T2 Zeroize + Keyring.destroy（内存加固）", done: true }
  - { title: "M14-T3 bench 标签用例 + RUNBOOK + AUDIT-PREP 准备包", done: true }
---

# M14 · 加固

ADR-4 国密落地（CipherSuite 一键切换并跑通解封链）+ 内存清零系统化 + 性能基线 + 审计入口包。
外部审计本身由外部机构执行（AUDIT-PREP 为入口）；mlock/JMH 工程化按 G1/RUNBOOK 挂账。
