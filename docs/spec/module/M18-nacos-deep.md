---
id: M18
title: Nacos 深接（服务注册 · MCP endpoint · 元数据富化 · namespace 多租户）
status: planned
sprint: v0.6
progress: 0
manualProgress: false
desc: "把'Nacos 原生'从一个集成点做成统一控制+发现平面：custos-host 注册为 Nacos 服务（健康检查+互相发现，支撑 v0.7 集群）+ MCP server endpoint 经 Nacos naming 注册（DIRECT/REF）+ MCP 工具元数据富化（tool.annotations 带 policy/ABAC 标记）与版本化 + namespace=租户 真隔离多租户策略/配置。"
docs:
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "Nacos AI 中心杠杆点研究", path: "docs/research/nacos-ai-center.md" }
---

# M18 · Nacos 深接（规划 · v0.6）

承接定位主线（Nacos 发现、Custos 执行）：当前 Nacos 只干"一个 policy 配置热推"一件事，本模块把它升为统一控制+发现平面。

## 范围（规划）

- 服务注册发现：custos-host 注册到 Nacos + 健康检查 + 多 host 互相发现（支撑 v0.7 集群）
- MCP endpoint 注册：custos MCP server 端点经 Nacos naming 注册（DIRECT 单点 / REF 服务引用）
- MCP 工具元数据富化：`tool.annotations` 携带 policy/ABAC 标记；MCP server 版本化（版本信号 ABAC schema 破坏性变更）
- namespace = 租户：Nacos namespace 真隔离多租户策略/配置（M08 的 domain 落到 namespace）

> 动工时走 brainstorm → spec → plan → impl，届时补 subtasks 与锚。第三方 API（Nacos 3.2 naming/AI admin）写计划前须源码/文档核准。
