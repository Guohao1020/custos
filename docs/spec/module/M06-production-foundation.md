---
id: M06
title: 生产基座（模块化单体 + SPI + custos-host + CLI）
status: done
sprint: Production
progress: 100
manualProgress: false
desc: "SecretsEngine SPI 化 · custos-host（启动即 sealed + REST admin 解封流 + secretless 查询面）· MCP transport · picocli CLI · 端到端宿主 IT。mvn -B verify 7 模块全绿。"
docs:
  - { title: "生产架构 spec", path: "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md" }
  - { title: "实现计划 · 生产基座", path: "docs/superpowers/plans/2026-06-09-custos-production-foundation.md" }
subtasks:
  - { title: "PF-T1 SecretsEngine SPI + Registry + Broker 解耦", done: true }
  - { title: "PF-T2 custos-host 脚手架 + 配置绑定 + sealed 引导", done: true }
  - { title: "PF-T3 OperatorService 解封生命周期 + REST admin + Bearer", done: true }
  - { title: "PF-T4 policy/audit/query_db 端点（未解封 409、secretless）", done: true }
  - { title: "PF-T5 MCP stdio runner + custos-cli", done: true }
  - { title: "PF-T6 端到端宿主 IT + compose 真跑 + demo 真命令", done: true }
---

# M06 · 生产基座

把 v0.1 五模块收敛为可端到端运行的生产形态：SPI 可插拔 + 解封后装配运营组件 + REST admin/CLI/MCP 多 transport。
