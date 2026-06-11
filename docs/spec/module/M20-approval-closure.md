---
id: M20
title: 审批闭环（REQUIRE_APPROVAL 持久化 · 队列 · 放行）
status: planned
sprint: v0.6
progress: 0
manualProgress: false
desc: "三态决策的第三态落地：AbacPdp 判出 REQUIRE_APPROVAL 时，broker 把 pending 审批请求持久化（agent/工具/资源/风险/原因）并返回'待审批'而非直接拒；审批 REST（列队列 / approve / deny）+ 审批通过后原请求在 TTL 窗内放行。M16 console 审批队列面板的后端前置。"
docs:
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "ABAC 三态设计（REQUIRE_APPROVAL 来源）", path: "docs/superpowers/specs/2026-06-09-custos-abac-design.md" }
  - { title: "Admin Console 设计（审批面板消费方）", path: "docs/superpowers/specs/2026-06-11-admin-console-design.md" }
---

# M20 · 审批闭环（规划 · v0.6，M16 前置）

M08 的 AbacPdp 已能判出三态 ALLOW/DENY/REQUIRE_APPROVAL，但第三态目前只有占位 `DenyApprovalHook`，
没有持久化/队列/放行——审批是空的。本模块把它做实，作为 M16 console 审批队列面板的后端。

## 范围（规划）

- **pending 落库**：broker 收到 REQUIRE_APPROVAL → 持久化 PendingApproval（id/agent/tool/resource/sql 摘要/风险分/原因/ts/status），返回"待审批 + approvalId"而非直接拒。
- **审批 REST**（admin-gated）：`GET /approvals`（pending 列表）、`POST /approvals/{id}/approve`、`POST /approvals/{id}/deny`。
- **放行**：approve 后，原 agent 在 TTL 窗内重发请求 → 命中已批准记录 → 放行签发；deny/超时 → 拒。每个审批动作落哈希链审计。
- **存储**：审批记录经 Storage（Barrier 加密 KV）或专用表持久化（写 spec 时定）。

> 动工时走 brainstorm → spec → plan → impl，届时补 subtasks 与 code/doc 锚。放行语义（同步等待 vs 异步重发）写 spec 时拍板，倾向异步重发（简单、无长连接）。
