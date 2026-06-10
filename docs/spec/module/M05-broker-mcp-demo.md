---
id: M05
title: 经纪层 + MCP + docker-compose demo
status: done
sprint: v0.1
progress: 100
manualProgress: false
desc: "secretless 只读执行 · PEP 编排(verify→decide→issue→execute→revoke) · MCP query_db · Spring Boot 装配 · compose + AC1–AC8 runbook。4 IT 全绿。"
docs:
  - { title: "经纪层设计", path: "docs/design/06-secrets-broker.md" }
  - { title: "MVP 纵向线", path: "docs/design/07-mvp-vertical-slice.md" }
  - { title: "实现计划 5/5 · 经纪 + MCP + demo", path: "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md" }
  - { title: "Demo Runbook（AC1–AC8）", path: "examples/demo.md" }
subtasks:
  - id: M05-S1
    title: "实现凭证不出库的只读查询执行器"
    done: true
    code:
      - "broker/src/main/java/io/custos/broker/SecretlessQueryExecutor.java"
      - "broker/src/test/java/io/custos/broker/SecretlessQueryExecutorIT.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.9"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md:32-150"
  - id: M05-S2
    title: "编排经纪层策略执行点的完整决策链"
    done: true
    code:
      - "broker/src/main/java/io/custos/broker/BrokerService.java"
      - "broker/src/test/java/io/custos/broker/BrokerServiceIT.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.9"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md:237-407"
  - id: M05-S3
    title: "经 MCP 暴露查询工具并容器化全栈"
    done: true
    code:
      - "broker/src/main/java/io/custos/broker/McpQueryToolServer.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#3.9"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md:410-604"
  - id: M05-S4
    title: "编写端到端验收手册并逐条跑通"
    done: true
    code:
      - "examples/demo.md"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md#9"
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md:607-705"
---

# M05 · 经纪层 + MCP + Demo

PEP 用临时凭证 secretless 执行只读查询，只回结果绝不回凭证；MCP Java SDK 2.0-RC1 暴露 query_db。
