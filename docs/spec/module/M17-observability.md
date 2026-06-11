---
id: M17
title: 可观测性（metrics · 结构化日志 · tracing）
status: planned
sprint: v0.6
progress: 0
manualProgress: false
desc: "生产环境出问题可定位：Prometheus metrics（解封态/签发·撤销速率/决策 allow·deny·approve 计数/审批队列深度/租约存量）+ 结构化日志（决策与运维动作，凭证脱敏）+ 分布式 tracing（一次 query_db 全链路 span）。喂给 M16 console 与外部 Grafana。"
docs:
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
---

# M17 · 可观测性（规划 · v0.6）

当前零可观测——无 metrics、无结构化日志、无 tracing，生产出事无法定位。本模块补齐，并作为 M16 console 实时监控的数据源。

## 范围（规划）

- Prometheus metrics：解封态、签发/撤销速率、决策计数（allow/deny/approve）、审批队列深度、活跃租约/动态账号存量
- 结构化日志：决策与运维动作落 JSON 日志，**凭证/高权限密钥全程脱敏**
- 分布式 tracing：一次 query_db 的 token 校验→PDP→签发→执行→撤销→审计 全链路 span
- 暴露 `/actuator`/`/metrics`（受控），供 Grafana 与 M16 console 消费

> 动工时走 brainstorm → spec → plan → impl，届时补 subtasks 与锚。
