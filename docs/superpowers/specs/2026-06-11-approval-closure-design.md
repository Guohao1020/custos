---
id: SPEC-M20-APPROVAL-CLOSURE
type: spec
title: "M20 审批闭环（REQUIRE_APPROVAL 持久化 · 队列 · 异步放行）设计"
status: reviewing
date: 2026-06-11
desc: "三态决策第三态落地：REQUIRE_APPROVAL 落 pending、审批 REST 队列、agent 携 approvalId 异步重发放行（单次有效）。M16 console 审批面板的后端前置。"
---

# M20 审批闭环（Approval Closure）设计

## 0 · 背景与动机

M08 的 `AbacPdp` 已能判出三态 `ALLOW/DENY/REQUIRE_APPROVAL`，但 `Decision.requireApproval` 的 `allowed=false`，
broker 现在 `if (!d.allowed()) return denied(...)` **把"需审批"和"拒绝"混为一谈**——第三态实际等于拒绝，
`ApprovalHook` 只有恒 false 的 `DenyApprovalHook` 占位。M20 把第三态做实：落库 → 审批队列 → 异步放行。
是 M16 console 审批队列面板的后端前置（spec `2026-06-11-admin-console-design.md` §4.6）。

## 1 · 已确认的关键决策

| # | 决策 | 取舍 |
|---|---|---|
| D1 | **异步重发**放行 | broker 返回"待审批+approvalId"（非阻塞）；approve 后 agent 携 id 重发放行。无长连接/线程挂起，贴 stdio MCP 与无状态 REST |
| D2 | **专用 Jimmer 表 `custos_approval`** | 队列要按 status/时间过滤列表，表天生适合（与 custos_audit/custos_lease 同风）；审批记录非密钥，不需 Barrier 加密 |
| D3 | **QueryResult 加 `status` + `approvalId`** | 显式三态契约（ALLOWED/DENIED/PENDING）；保留 `allowed()`=status==ALLOWED 兼容 |
| D4 | **agent 携 approvalId 重发 + broker 校一致** | QueryIntent 加可选 approvalId；broker 校 APPROVED+未过期+agent/工具/资源一致+未消费 → 放行；**单次有效**防重放 |

## 2 · 架构与组件

```
engine                                  broker                              app
 ApprovalRow (Jimmer→custos_approval)    BrokerService.queryDb:              ApprovalController
 ApprovalStatus{PENDING/APPROVED/        ├ Effect.DENY → denied             ├ GET  /approvals (pending 列表)
   DENIED/CONSUMED}                       ├ Effect.REQUIRE_APPROVAL →        ├ POST /approvals/{id}/approve
 PendingApproval (domain record)         │   store.create(pending)          └ POST /approvals/{id}/deny
 ApprovalStore                           │   → QueryResult(PENDING,id)        (admin-gated;接 ApprovalStore)
  create/listPending/get/approve/        └ 带 approvalId 重发 →
  deny/markConsumed                          store 校一致 → 放行签发 → markConsumed
```

- **engine**：`ApprovalRow`（Jimmer 实体）+ `ApprovalStore`（基于 JSqlClient，与 `HashChainAuditLog`/`DefaultLeaseManager` 同款持久化层）+ `ApprovalStatus` 枚举 + `PendingApproval` 域记录。
- **broker**：`BrokerService` 注入 `ApprovalStore`；queryDb 分流 `Effect`（见 §3）。`QueryResult`/`QueryIntent` 契约变更。
- **app**：`ApprovalController` + 解封装配注入 `ApprovalStore`（`UnsealedContext` 加访问器）。`/approvals` 进 `AdminTokenFilter`。
- **authz 层不动**：`AbacPdp` 照旧吐 `REQUIRE_APPROVAL`（`DenyApprovalHook` 保留——异步审批下不再内联自动放行）。

## 3 · 数据流（异步重发）

1. **触发**：agent query → PDP 判 `REQUIRE_APPROVAL` → broker `store.create(PendingApproval)` 落 `status=PENDING, expire_at=null`（待批）→ 返回 `QueryResult(status=PENDING, approvalId=<id>)` → 审计 `decision=require-approval`。
2. **审批**：运维 `GET /approvals` 见 pending → `POST /approvals/{id}/approve` → `status=APPROVED, decided_at=now, expire_at=now+window`（默认 15min）→ 审计 `decision=approve, actor=admin`。（`deny` → `status=DENIED` + 审计。）
3. **放行**：agent 携 `approvalId` 重发 → broker 校：`token.verify` ok + `store.get(id)` 为 `APPROVED` + `now<expire_at` + (agent/tool/resource) 与当前请求一致 + 未 CONSUMED → 跳过审批闸、签发动态凭证执行 → `store.markConsumed(id)` → 审计 `decision=allow(approved)`。
4. **拒绝路径**：id 为 DENIED/过期/不一致/已 CONSUMED → `QueryResult.denied(原因)` + 审计。

## 4 · 契约变更（最小）

```java
// broker
public enum QueryStatus { ALLOWED, DENIED, PENDING }
public record QueryResult(QueryStatus status, List<Map<String,Object>> rows, String denyReason, String approvalId) {
    public boolean allowed() { return status == QueryStatus.ALLOWED; }
    public static QueryResult ok(List<...> rows) { return new QueryResult(ALLOWED, rows, null, null); }
    public static QueryResult denied(String reason) { return new QueryResult(DENIED, List.of(), reason, null); }
    public static QueryResult pending(String approvalId) { return new QueryResult(PENDING, List.of(), "awaiting approval", approvalId); }
}
// QueryIntent 加可选 approvalId（null=首次请求）
public record QueryIntent(String tool, String resource, String role, String sql, String approvalId) { /* 旧构造器默认 approvalId=null */ }
```
- MCP `query_db` 加可选 `approvalId` 入参；handle 在 PENDING 时回 `PENDING: <id>（待审批）`。
- 现有调用方（QueryController/McpQueryToolServer/IT）随契约更新；`allowed()` 兼容旧断言。

## 5 · 数据模型 `custos_approval`

| 列 | 类型 | 说明 |
|---|---|---|
| id | VARCHAR PK | 审批单 id（生成） |
| agent / tool / resource / role | VARCHAR | 请求主体与目标（重发一致性校验依据） |
| sql_digest | VARCHAR | SQL 摘要（展示用，不存全文敏感） |
| risk | INT | 风险分 |
| reason | VARCHAR | PDP 给的需审批原因 |
| status | VARCHAR | PENDING/APPROVED/DENIED/CONSUMED |
| created_at / decided_at / expire_at | BIGINT | 创建/裁决/有效窗到期 |

加进 `engine/src/main/resources/db/schema.sql` 与 `examples/init/schema.sql`。

## 6 · 测试（TDD）

- `ApprovalStoreIT`（Testcontainers）：create→listPending 含它→approve→get APPROVED→markConsumed→CONSUMED；deny→DENIED；过期判定。
- `BrokerService` 审批流 IT：装 `AbacPdp`（中风险 `RiskScorer` + `DenyApprovalHook` → REQUIRE_APPROVAL）→ 首次 query 得 PENDING+id；approve；携 id 重发 → ALLOWED + 真签发查得行；① 无 id 重发仍 PENDING ② DENIED id → denied ③ 过期 → denied ④ 资源不一致 → denied ⑤ 已 CONSUMED 二次重发 → denied。
- `ApprovalControllerIT`（Spring+Testcontainers）：GET/approve/deny；无 token → 401。
- 回归：现有 BrokerServiceIT/McpQueryToolServerTest 随 QueryResult/QueryIntent 契约更新仍绿。

## 7 · 验收标准

- 中风险请求首次返回 `status=PENDING + approvalId`（非 denied）；`GET /approvals` 能列出；approve 后 agent 携 id 重发签发成功并查得数据；deny/过期/不一致/重放均 denied。
- 每个审批动作（require-approval / approve / deny / allow-approved）落哈希链审计，`audit verify` 不断链。
- `/approvals*` 无 token → 401。
- `mvn -B clean verify` 全绿（含新 IT + 契约回归）。

## 8 · 不做（YAGNI）

- 不做 console 审批面板（=M16，调本模块 REST）。
- 不改 `AbacPdp` 的风险评分/策略逻辑（=M08）。
- 单审批人模型：审批者=admin token 持有者；per-approver 身份/多级审批留后。
- 不做审批通知（webhook/IM 推送）；运维主动轮询 `GET /approvals`（或经 M16 console）。
- approve 单次有效（CONSUMED）；不做"批准后窗内多次复用"。
