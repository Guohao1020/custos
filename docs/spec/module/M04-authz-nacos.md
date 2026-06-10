---
id: M04
title: 策略层（jCasbin RBAC + Nacos 秒级吊销）
status: done
sprint: v0.1
progress: 100
manualProgress: false
desc: "jCasbin RBAC 可解释 PDP（默认拒/deny 优先）· ControlPlane/Watcher 秒级吊销 · NacosControlPlane。4 单测全绿。"
docs:
  - { title: "策略层设计", path: "docs/design/04-authz-design.md" }
  - { title: "Nacos 集成设计", path: "docs/design/05-nacos-integration.md" }
  - { title: "实现计划 4/5 · 策略 + Nacos", path: "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-authz-nacos.md" }
subtasks:
  - { title: "P4-T1 jCasbin RBAC 可解释 PDP", done: true }
  - { title: "P4-T2 ControlPlane + PolicyWatcher 秒级吊销", done: true }
  - { title: "P4-T3 NacosControlPlane + 环境门控冒烟 IT", done: true }
---

# M04 · 策略层 jCasbin + Nacos

PDP 用 enforceEx 给可解释决策；策略存 Nacos，改策略经 Watcher 重载令决策秒级翻转（吊销）。
