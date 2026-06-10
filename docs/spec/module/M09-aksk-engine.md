---
id: M09
title: AK·SK secrets engine + 轮换
status: done
sprint: v0.2
progress: 100
manualProgress: false
desc: "SecretsEngine SPI 第二实现：AkSkProvider SPI + 内存模拟 · AkSkSecretsEngine issue/revoke 复用 LeaseManager · rotate 支持 grace 多版本过渡/硬轮换。engine 27 单测全绿（纯单元无 Docker）。"
docs:
  - { title: "AK·SK 设计 spec", path: "docs/superpowers/specs/2026-06-09-custos-aksk-design.md" }
  - { title: "AK·SK 实现计划", path: "docs/superpowers/plans/2026-06-09-custos-aksk.md" }
  - { title: "经纪层设计", path: "docs/design/06-secrets-broker.md" }
subtasks:
  - id: M09-S1
    title: "定义 AK·SK 凭证对与可插拔提供方"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/secrets/AkSkPair.java"
      - "engine/src/main/java/io/custos/engine/secrets/AkSkProvider.java"
      - "engine/src/main/java/io/custos/engine/secrets/InMemoryAkSkProvider.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-aksk-design.md#3. 组件与契约"
      - "docs/superpowers/plans/2026-06-09-custos-aksk.md:29-150"
  - id: M09-S2
    title: "实现 AK·SK 的签发撤销与宽限轮换"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/secrets/AkSkSecretsEngine.java"
      - "engine/src/test/java/io/custos/engine/secrets/AkSkSecretsEngineTest.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-aksk-design.md#2. 架构与数据流"
      - "docs/superpowers/specs/2026-06-09-custos-aksk-design.md#4. 轮换语义（多版本过渡）"
      - "docs/superpowers/plans/2026-06-09-custos-aksk.md:153-333"
---

# M09 · AK·SK secrets engine

SecretsEngine SPI 第二实现，证明引擎可扩展性：动态签发短时 AK·SK、TTL 到期自动撤销、grace 多版本过渡轮换。
真实 AWS STS / 阿里云 RAM 作 AkSkProvider 未来实现。
