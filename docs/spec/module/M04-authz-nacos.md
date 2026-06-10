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
  - id: M04-S1
    title: "用 jCasbin 实现可解释的 RBAC 决策点"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/CasbinPdp.java"
      - "authz/src/test/java/io/custos/authz/CasbinPdpTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-authz-nacos.md#Task 1:"
      - "docs/design/04-authz-design.md#4. 可解释决策"
  - id: M04-S2
    title: "策略控制面订阅推送实现秒级吊销"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/PolicyWatcher.java"
      - "authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-authz-nacos.md#Task 2:"
      - "docs/design/05-nacos-integration.md#2. 配置热更新 = 秒级权限变更与吊销"
  - id: M04-S3
    title: "对接真实 Nacos 的环境门控冒烟验证"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/NacosControlPlane.java"
      - "authz/src/test/java/io/custos/authz/NacosControlPlaneSmokeIT.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-authz-nacos.md#Task 3:"
      - "docs/design/05-nacos-integration.md#1. 注册什么"
---

# M04 · 策略层 jCasbin + Nacos

PDP 用 enforceEx 给可解释决策；策略存 Nacos，改策略经 Watcher 重载令决策秒级翻转（吊销）。
