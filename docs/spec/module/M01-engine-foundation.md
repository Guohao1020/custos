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
  - id: M01-S1
    title: "搭建 Maven 多模块工程骨架并接通 CI 冒烟门禁"
    done: true
    code:
      - "pom.xml"
      - "engine/pom.xml"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:38-45"
  - id: M01-S2
    title: "实现国际套件的 AES-256-GCM 认证加密"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/CipherSuite.java"
      - "engine/src/main/java/io/custos/engine/crypto/IntlSuite.java:1-58"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:179-245"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:37-50"
  - id: M01-S3
    title: "提供 SHA-256 摘要与 HMAC 消息认证"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/IntlSuite.java:61-78"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:406-445"
  - id: M01-S4
    title: "提供 ECDSA P-256 签名与验签"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/IntlSuite.java:81-103"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:492-574"
  - id: M01-S5
    title: "实现多版本密钥环与信封加密屏障"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/crypto/Keyring.java"
      - "engine/src/main/java/io/custos/engine/barrier/DefaultBarrier.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:590-688"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:51-60"
  - id: M01-S6
    title: "封装 Shamir 秘密分片的拆分与重建"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/seal/ShamirSplitter.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:763-811"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:61-72"
  - id: M01-S7
    title: "打通密钥层级与密封解封生命周期"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/seal/SealManager.java"
      - "engine/src/main/java/io/custos/engine/seal/DefaultSealManager.java:1-98"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md:869-1056"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:61-72"
---

# M01 · 引擎基座

自研密码内核：可切换 CipherSuite（国际套件落地，国密预留）、Barrier 信封加密、Shamir 解封、密钥层级。
