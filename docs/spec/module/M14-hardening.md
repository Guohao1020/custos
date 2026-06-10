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
  - id: M14-S1
    title: "落地国密套件并跑通完整密封链"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/GmSuite.java"
      - "engine/src/main/java/io/custos/engine/crypto/CipherSuite.java:16-17"
      - "engine/src/test/java/io/custos/engine/crypto/GmSuiteTest.java"
    docs:
      - "docs/superpowers/specs/2026-06-10-custos-hardening-design.md#GmSuite"
      - "docs/superpowers/plans/2026-06-10-custos-hardening.md#Task 1"
  - id: M14-S2
    title: "系统化清零内存中的密钥材料"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/Zeroize.java"
      - "engine/src/main/java/io/custos/engine/crypto/Keyring.java:30-34"
      - "engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java:87-91"
    docs:
      - "docs/superpowers/specs/2026-06-10-custos-hardening-design.md#内存加固"
      - "docs/superpowers/plans/2026-06-10-custos-hardening.md#Task 2"
  - id: M14-S3
    title: "建立性能基线与外部审计准备包"
    done: true
    code:
      - "engine/src/test/java/io/custos/engine/bench/BarrierBenchSmokeTest.java:15"
      - "docs/bench/RUNBOOK.md:6-18"
      - "docs/bench/RUNBOOK.md:20-32"
    docs:
      - "docs/superpowers/specs/2026-06-10-custos-hardening-design.md#压测"
      - "docs/superpowers/specs/2026-06-10-custos-hardening-design.md#外部审计准备"
      - "docs/superpowers/plans/2026-06-10-custos-hardening.md#Task 3"
---

# M14 · 加固

ADR-4 国密落地（CipherSuite 一键切换并跑通解封链）+ 内存清零系统化 + 性能基线 + 审计入口包。
外部审计本身由外部机构执行（AUDIT-PREP 为入口）；mlock/JMH 工程化按 G1/RUNBOOK 挂账。
