---
id: M20
title: 审批闭环（REQUIRE_APPROVAL 持久化 · 队列 · 放行）
status: done
sprint: v0.6
progress: 100
manualProgress: false
desc: "三态决策的第三态落地：AbacPdp 判出 REQUIRE_APPROVAL 时，broker 把 pending 审批请求落 custos_approval 表（agent/工具/资源/风险/原因）并返回 PENDING+approvalId 而非直接拒；审批 REST（列队列 / approve / deny，admin-gated）+ 批准后 agent 在 15min 有效窗内携 approvalId 重发放行（单次有效防重放）。每个审批动作落哈希链审计。M16 console 审批队列面板的后端前置。"
docs:
  - { title: "审批闭环设计 spec", path: "docs/superpowers/specs/2026-06-11-approval-closure-design.md" }
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "ABAC 三态设计（REQUIRE_APPROVAL 来源）", path: "docs/superpowers/specs/2026-06-09-custos-abac-design.md" }
  - { title: "Admin Console 设计（审批面板消费方）", path: "docs/superpowers/specs/2026-06-11-admin-console-design.md" }
subtasks:
  - id: M20-S1
    title: "审批数据模型：三态枚举 + 域记录 + Jimmer 实体 + 容器栈建表"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/approval/ApprovalStatus.java
      - engine/src/main/java/io/custos/engine/approval/PendingApproval.java
      - engine/src/main/java/io/custos/engine/approval/ApprovalRow.java
      - engine/src/main/resources/db/schema.sql
    docs:
      - docs/superpowers/specs/2026-06-11-approval-closure-design.md#数据模型
  - id: M20-S2
    title: "审批记录持久化层（create/listPending/get/approve/deny/markConsumed）"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/approval/ApprovalStore.java
      - engine/src/main/java/io/custos/engine/approval/JimmerApprovalStore.java
      - engine/src/test/java/io/custos/engine/approval/JimmerApprovalStoreIT.java
  - id: M20-S3
    title: "三态查询契约：QueryStatus 枚举 + QueryResult.status/approvalId + QueryIntent.approvalId + MCP 入参"
    done: true
    code:
      - broker/src/main/java/io/custos/broker/QueryStatus.java
      - broker/src/main/java/io/custos/broker/QueryResult.java
      - broker/src/main/java/io/custos/broker/QueryIntent.java
      - broker/src/main/java/io/custos/broker/McpQueryToolServer.java
    docs:
      - docs/superpowers/specs/2026-06-11-approval-closure-design.md#契约变更
  - id: M20-S4
    title: "broker 审批流：REQUIRE_APPROVAL 落 pending → 携 id 重发校一致放行 → markConsumed（单次有效）"
    done: true
    code:
      - broker/src/main/java/io/custos/broker/BrokerService.java
    docs:
      - docs/superpowers/specs/2026-06-11-approval-closure-design.md#数据流
  - id: M20-S5
    title: "审批 REST（admin-gated GET/approve/deny）+ 解封装配注入 ApprovalStore"
    done: true
    code:
      - app/src/main/java/io/custos/app/approval/ApprovalController.java
      - app/src/main/java/io/custos/app/operator/OperatorService.java
  - id: M20-S6
    title: "收尾：容器栈 schema 同步 + demo AC10 审批闭环"
    done: true
    docs:
      - examples/demo.md
---

# M20 · 审批闭环（Approval Closure · v0.6，M16 前置）

M08 的 AbacPdp 已能判出三态 ALLOW/DENY/REQUIRE_APPROVAL，但第三态此前只有占位 `DenyApprovalHook`，
没有持久化/队列/放行——审批是空的。本模块把它做实，作为 M16 console 审批队列面板的后端。已落地、`mvn -B clean verify` 全绿。

## 已落地范围

- **pending 落库**：broker 收到 REQUIRE_APPROVAL → 把 PendingApproval（id/agent/tool/resource/role/风险分/原因/ts/status）落专用表 `custos_approval`，返回 `{"status":"PENDING","approvalId":"..."}` 而非直接拒。
- **审批 REST**（admin-gated，经 `AdminTokenFilter`）：`GET /approvals`（pending 列表）、`POST /approvals/{id}/approve`（给 15min 有效窗）、`POST /approvals/{id}/deny`。
- **异步重发放行**：approve 后，原 agent 在有效窗内携 `approvalId` 重发 → broker 校 APPROVED + 未过期 + agent/tool/resource 一致 + 未消费 → 放行签发动态凭证执行 → `markConsumed`（**单次有效防重放**）；deny / 超时 / 不一致 / 已消费 → denied。每个审批动作（require-approval / approve / deny / allow-approved）落哈希链审计。
- **存储取舍**：审批记录非密钥，用专用 Jimmer 表 `custos_approval`（按 status/时间过滤列表，与 custos_audit/custos_lease 同风），不走 Barrier 加密。

> 放行语义采**异步重发**（agent 携 approvalId 重发，无长连接/线程挂起），贴 stdio MCP 与无状态 REST。
> 演示见 `examples/demo.md` 的 AC10；不做项（console 面板=M16、风险评分=M08、通知推送、多级审批）见 spec §8。
