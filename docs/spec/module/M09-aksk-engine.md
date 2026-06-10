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
  - { title: "M09-T1 AkSkPair + AkSkProvider SPI + InMemoryAkSkProvider", done: true }
  - { title: "M09-T2 AkSkSecretsEngine（issue/revoke/rotate with grace）", done: true }
---

# M09 · AK·SK secrets engine

SecretsEngine SPI 第二实现，证明引擎可扩展性：动态签发短时 AK·SK、TTL 到期自动撤销、grace 多版本过渡轮换。
真实 AWS STS / 阿里云 RAM 作 AkSkProvider 未来实现。
