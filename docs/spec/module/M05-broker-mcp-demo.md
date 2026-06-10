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
  - { title: "P5-T1 SecretlessQueryExecutor 只读执行（IT）", done: true }
  - { title: "P5-T2 BrokerService PEP 编排（IT）", done: true }
  - { title: "P5-T3 MCP query_db + Spring Boot app + docker-compose", done: true }
  - { title: "P5-T4 端到端 demo runbook（AC1–AC8）", done: true }
---

# M05 · 经纪层 + MCP + Demo

PEP 用临时凭证 secretless 执行只读查询，只回结果绝不回凭证；MCP Java SDK 2.0-RC1 暴露 query_db。
