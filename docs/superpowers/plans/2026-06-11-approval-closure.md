# M20 审批闭环（Approval Closure）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 AbacPdp 三态的 REQUIRE_APPROVAL 做实：落 `custos_approval` 表、审批 REST 队列、agent 携 approvalId 异步重发放行（单次有效）。

**Architecture:** engine 加 `ApprovalRow`(Jimmer→custos_approval) + `ApprovalStore` 接口/`JimmerApprovalStore` 实现（仿 `HashChainAuditLog` 的 JSqlClient 持久化）；broker 的 `BrokerService` 分流 `Effect`（DENY→拒、REQUIRE_APPROVAL→落 pending+返回 PENDING+id、带 id 重发→校一致→签发→标 CONSUMED）；`QueryResult`/`QueryIntent` 加字段；app 加 `ApprovalController` + 解封装配注入。

**Tech Stack:** Java 21 · Maven · Jimmer（@Entity + APT 生成 Draft，仿 AuditRow）· Testcontainers MySQL 1.19.8（api.version=1.40）· Spring Boot Web 3.3.2。零新增依赖。

**前置：** 分支 `impl/m20-approval-closure`（已建，M20 spec 在 main）。spec：`docs/superpowers/specs/2026-06-11-approval-closure-design.md`。authz 层不改（AbacPdp 照旧吐 REQUIRE_APPROVAL）。

**红线：** 审批记录非密钥（agent/tool/resource/risk/reason），明文存表即可；每个审批动作落哈希链审计；放行单次有效（CONSUMED）防重放；`/approvals*` admin-gated。

---

### Task 1: 审批数据模型 + 表 schema

**Files:**
- Create: `engine/src/main/java/io/custos/engine/approval/ApprovalStatus.java`
- Create: `engine/src/main/java/io/custos/engine/approval/PendingApproval.java`
- Create: `engine/src/main/java/io/custos/engine/approval/ApprovalRow.java`（Jimmer @Entity）
- Modify: `engine/src/main/resources/db/schema.sql`（加 custos_approval）
- Test: `engine/src/test/java/io/custos/engine/approval/PendingApprovalTest.java`

- [ ] **Step 1: 写失败单测**
```java
package io.custos.engine.approval;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingApprovalTest {
    @Test void recordHoldsFields() {
        PendingApproval p = new PendingApproval("a1", "agent:claude-prod", "db/query_orders", "appdb", "read-only",
                55, "中风险需审批: risk=55", ApprovalStatus.PENDING, 1000L, 0L, 0L);
        assertEquals("a1", p.id());
        assertEquals(ApprovalStatus.PENDING, p.status());
        assertEquals(55, p.risk());
    }
}
```

- [ ] **Step 2: 跑确认失败** — Run: `mvn -pl engine test -Dtest=PendingApprovalTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 3: 写实现**
```java
// ApprovalStatus.java
package io.custos.engine.approval;
/** 审批单状态。 */
public enum ApprovalStatus { PENDING, APPROVED, DENIED, CONSUMED }
```
```java
// PendingApproval.java
package io.custos.engine.approval;
/** 一条审批单的域视图（非密钥，明文持久化）。expireAt=approve 后的有效窗到期(ms)；decidedAt=裁决时刻。 */
public record PendingApproval(String id, String agent, String tool, String resource, String role,
                              int risk, String reason, ApprovalStatus status,
                              long createdAt, long decidedAt, long expireAt) {}
```
```java
// ApprovalRow.java —— 仿 engine/.../audit/AuditRow.java（@Entity + APT 生成 Draft）
package io.custos.engine.approval;
import org.babyfish.jimmer.sql.*;

/** 审批单行。id 为应用生成的字符串主键（非自增）；status 流转 PENDING→APPROVED/DENIED→CONSUMED。 */
@Entity
@Table(name = "custos_approval")
public interface ApprovalRow {
    @Id String id();
    String agent();
    String tool();
    String resource();
    String role();
    int risk();
    String reason();
    String status();
    @Column(name = "created_at") long createdAt();
    @Column(name = "decided_at") long decidedAt();
    @Column(name = "expire_at") long expireAt();
}
```

- [ ] **Step 4: 加表 schema** — 在 `engine/src/main/resources/db/schema.sql` 末尾追加：
```sql
CREATE TABLE IF NOT EXISTS custos_approval (
  id          VARCHAR(160) PRIMARY KEY,
  agent       VARCHAR(512) NOT NULL,
  tool        VARCHAR(256) NOT NULL,
  resource    VARCHAR(256) NOT NULL,
  role        VARCHAR(128) NOT NULL,
  risk        INT NOT NULL,
  reason      VARCHAR(512),
  status      VARCHAR(32) NOT NULL,
  created_at  BIGINT NOT NULL,
  decided_at  BIGINT NOT NULL DEFAULT 0,
  expire_at   BIGINT NOT NULL DEFAULT 0
);
```

- [ ] **Step 5: 跑确认通过（含 APT 生成 ApprovalRowDraft）** — Run: `mvn -pl engine test -Dtest=PendingApprovalTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS（编译触发 Jimmer APT 生成 Draft/Table/Props）

- [ ] **Step 6: Commit**
```bash
git add engine/src/main/java/io/custos/engine/approval/ engine/src/main/resources/db/schema.sql engine/src/test/java/io/custos/engine/approval/PendingApprovalTest.java
git commit -m "feat(engine): approval data model + custos_approval table"
```
body 末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 2: ApprovalStore（Jimmer 持久化）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/approval/ApprovalStore.java`（接口）
- Create: `engine/src/main/java/io/custos/engine/approval/JimmerApprovalStore.java`
- Test: `engine/src/test/java/io/custos/engine/approval/JimmerApprovalStoreIT.java`

- [ ] **Step 1: 写接口**
```java
package io.custos.engine.approval;
import java.util.List;
import java.util.Optional;
/** 审批单持久化。create 生成 id 并落 PENDING；approve/deny/markConsumed 按 id 流转状态。 */
public interface ApprovalStore {
    String create(String agent, String tool, String resource, String role, int risk, String reason);
    Optional<PendingApproval> get(String id);
    List<PendingApproval> listPending();
    void approve(String id, long expireAt);
    void deny(String id);
    void markConsumed(String id);
}
```

- [ ] **Step 2: 写失败 IT**（Testcontainers + JimmerClients，参照 `engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java` 的容器/建表/JSqlClient 样板；建 custos_approval 表用 Task 1 schema 的 SQL）
```java
package io.custos.engine.approval;
// import：MySQLContainer、JimmerClients、JSqlClient、MysqlDataSource、java.sql.*、Testcontainers、junit
@org.testcontainers.junit.jupiter.Testcontainers
class JimmerApprovalStoreIT {
    // @Container MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root")
    // @BeforeEach: 建 custos_approval 表（Task 1 的 CREATE TABLE）；sql = JimmerClients.of(ds)

    @org.junit.jupiter.api.Test
    void createListApproveConsume() {
        ApprovalStore store = new JimmerApprovalStore(sql);
        String id = store.create("agent:claude-prod", "db/query_orders", "appdb", "read-only", 55, "需审批");
        org.junit.jupiter.api.Assertions.assertEquals(1, store.listPending().size());
        org.junit.jupiter.api.Assertions.assertEquals(ApprovalStatus.PENDING, store.get(id).orElseThrow().status());
        long exp = 9999999999999L;
        store.approve(id, exp);
        org.junit.jupiter.api.Assertions.assertEquals(ApprovalStatus.APPROVED, store.get(id).orElseThrow().status());
        org.junit.jupiter.api.Assertions.assertEquals(exp, store.get(id).orElseThrow().expireAt());
        org.junit.jupiter.api.Assertions.assertTrue(store.listPending().isEmpty());   // approve 后不在 pending
        store.markConsumed(id);
        org.junit.jupiter.api.Assertions.assertEquals(ApprovalStatus.CONSUMED, store.get(id).orElseThrow().status());
    }
    @org.junit.jupiter.api.Test
    void denyMovesOutOfPending() {
        ApprovalStore store = new JimmerApprovalStore(sql);
        String id = store.create("agent:x", "db/q", "appdb", "read-only", 60, "r");
        store.deny(id);
        org.junit.jupiter.api.Assertions.assertEquals(ApprovalStatus.DENIED, store.get(id).orElseThrow().status());
        org.junit.jupiter.api.Assertions.assertTrue(store.listPending().isEmpty());
    }
}
```

- [ ] **Step 3: 跑确认失败** — Run: `mvn -pl engine test -Dtest=JimmerApprovalStoreIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 4: 写实现**（save 模式仿 HashChainAuditLog：create 用 INSERT_ONLY；状态流转用 update by id）
```java
package io.custos.engine.approval;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** custos_approval 的 Jimmer 持久化。id = 16 字节随机 hex（仅 [0-9a-f]）。 */
public final class JimmerApprovalStore implements ApprovalStore {
    private final JSqlClient sql;
    private final SecureRandom random = new SecureRandom();
    public JimmerApprovalStore(JSqlClient sql) { this.sql = sql; }

    @Override
    public String create(String agent, String tool, String resource, String role, int risk, String reason) {
        String id = hex(16);
        long now = System.currentTimeMillis();
        sql.getEntities().saveCommand(ApprovalRowDraft.$.produce(d -> {
            d.setId(id); d.setAgent(agent); d.setTool(tool); d.setResource(resource); d.setRole(role);
            d.setRisk(risk); d.setReason(reason == null ? "" : reason);
            d.setStatus(ApprovalStatus.PENDING.name());
            d.setCreatedAt(now); d.setDecidedAt(0L); d.setExpireAt(0L);
        })).setMode(SaveMode.INSERT_ONLY).execute();
        return id;
    }

    @Override
    public Optional<PendingApproval> get(String id) {
        ApprovalTable t = ApprovalTable.$;
        return sql.createQuery(t).where(t.id().eq(id)).select(t).execute().stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<PendingApproval> listPending() {
        ApprovalTable t = ApprovalTable.$;
        return sql.createQuery(t).where(t.status().eq(ApprovalStatus.PENDING.name()))
                .orderBy(t.createdAt().asc()).select(t).execute().stream().map(this::toDomain).toList();
    }

    @Override public void approve(String id, long expireAt) { setStatus(id, ApprovalStatus.APPROVED, System.currentTimeMillis(), expireAt); }
    @Override public void deny(String id) { setStatus(id, ApprovalStatus.DENIED, System.currentTimeMillis(), 0L); }
    @Override public void markConsumed(String id) {
        ApprovalRow cur = require(id);
        setStatus(id, ApprovalStatus.CONSUMED, cur.decidedAt(), cur.expireAt());
    }

    private void setStatus(String id, ApprovalStatus st, long decidedAt, long expireAt) {
        ApprovalRow cur = require(id);
        sql.getEntities().saveCommand(ApprovalRowDraft.$.produce(d -> {
            d.setId(id); d.setAgent(cur.agent()); d.setTool(cur.tool()); d.setResource(cur.resource()); d.setRole(cur.role());
            d.setRisk(cur.risk()); d.setReason(cur.reason());
            d.setStatus(st.name()); d.setCreatedAt(cur.createdAt()); d.setDecidedAt(decidedAt); d.setExpireAt(expireAt);
        })).setMode(SaveMode.UPSERT).execute();
    }

    private ApprovalRow require(String id) {
        ApprovalTable t = ApprovalTable.$;
        return sql.createQuery(t).where(t.id().eq(id)).select(t).execute().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no approval: " + id));
    }

    private PendingApproval toDomain(ApprovalRow r) {
        return new PendingApproval(r.id(), r.agent(), r.tool(), r.resource(), r.role(),
                r.risk(), r.reason(), ApprovalStatus.valueOf(r.status()), r.createdAt(), r.decidedAt(), r.expireAt());
    }

    private String hex(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
}
```
> `ApprovalTable`/`ApprovalRowDraft` 由 Jimmer APT 生成（Task 1 编译已生成）。若 APT 未生成 `ApprovalTable.$`，先 `mvn -pl engine compile` 触发。

- [ ] **Step 5: 跑确认通过** — Run: `mvn -pl engine test -Dtest=JimmerApprovalStoreIT -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS

- [ ] **Step 6: Commit**
```bash
git add engine/src/main/java/io/custos/engine/approval/ApprovalStore.java engine/src/main/java/io/custos/engine/approval/JimmerApprovalStore.java engine/src/test/java/io/custos/engine/approval/JimmerApprovalStoreIT.java
git commit -m "feat(engine): ApprovalStore + JimmerApprovalStore"
```

---

### Task 3: broker 契约（QueryResult/QueryIntent 加字段）

**Files:**
- Modify: `broker/src/main/java/io/custos/broker/QueryResult.java`
- Create: `broker/src/main/java/io/custos/broker/QueryStatus.java`
- Modify: `broker/src/main/java/io/custos/broker/QueryIntent.java`
- Modify: `broker/src/main/java/io/custos/broker/McpQueryToolServer.java`（approvalId 入参 + PENDING 文本）

- [ ] **Step 1: 写 QueryStatus + 改 QueryResult**
```java
// QueryStatus.java
package io.custos.broker;
public enum QueryStatus { ALLOWED, DENIED, PENDING }
```
```java
// QueryResult.java
package io.custos.broker;
import java.util.List;
import java.util.Map;
/** 经纪返回：三态 + 行数据/拒因/审批 id。**永不含任何凭证**。 */
public record QueryResult(QueryStatus status, List<Map<String, Object>> rows, String denyReason, String approvalId) {
    public boolean allowed() { return status == QueryStatus.ALLOWED; }
    public static QueryResult ok(List<Map<String, Object>> rows) { return new QueryResult(QueryStatus.ALLOWED, rows, null, null); }
    public static QueryResult denied(String reason) { return new QueryResult(QueryStatus.DENIED, List.of(), reason, null); }
    public static QueryResult pending(String approvalId) { return new QueryResult(QueryStatus.PENDING, List.of(), "awaiting approval", approvalId); }
}
```

- [ ] **Step 2: 改 QueryIntent**（加可选 approvalId，保留 4 参便捷构造）
```java
package io.custos.broker;
/** 查询意图：tool/resource/role/sql；approvalId 为审批重发时携带（null=首次请求）。 */
public record QueryIntent(String tool, String resource, String role, String sql, String approvalId) {
    public QueryIntent(String tool, String resource, String role, String sql) { this(tool, resource, role, sql, null); }
    public QueryIntent(String tool, String resource, String sql) { this(tool, resource, "read-only", sql, null); }
}
```

- [ ] **Step 3: 改 McpQueryToolServer**（inputSchema 加 `approvalId`；handle 透传 + PENDING 文本）—— 在 properties 加 `"approvalId", Map.of("type","string")`（不进 required）；handle：
```java
QueryResult r = b.queryDb(new QueryIntent((String)args.get("tool"), (String)args.get("resource"),
        (String)args.getOrDefault("role","read-only"), (String)args.get("sql"), (String)args.get("approvalId")),
        (String)args.get("userToken"));
String text = switch (r.status()) {
    case ALLOWED -> "rows=" + r.rows();
    case PENDING -> "PENDING: " + r.approvalId() + "（待审批，approve 后携 approvalId 重发）";
    case DENIED  -> "DENIED: " + r.denyReason();
};
return McpSchema.CallToolResult.builder()
        .content(List.of(McpSchema.TextContent.builder(text).build()))
        .isError(r.status() != QueryStatus.ALLOWED).build();
```

- [ ] **Step 4: 编译确认（BrokerService 仍用 ok/denied 工厂，不破坏）** — Run: `mvn -pl broker -am test-compile -Dsurefire.failIfNoSpecifiedTests=false` Expected: BUILD SUCCESS（QueryResult.ok/denied 工厂保留，BrokerService/QueryController/测试旧调用兼容；McpQueryToolServer 已更新）

- [ ] **Step 5: Commit**
```bash
git add broker/src/main/java/io/custos/broker/QueryStatus.java broker/src/main/java/io/custos/broker/QueryResult.java broker/src/main/java/io/custos/broker/QueryIntent.java broker/src/main/java/io/custos/broker/McpQueryToolServer.java
git commit -m "feat(broker): QueryResult tri-state + QueryIntent approvalId"
```

---

### Task 4: BrokerService 审批流

**Files:**
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`（构造器加 ApprovalStore；Effect 分流 + 重发放行）
- Modify: `broker/src/test/java/io/custos/broker/BrokerServiceIT.java`（审批流 IT）
- Modify: `broker/src/test/java/io/custos/broker/BrokerAuditWiringTest.java` + `McpQueryToolServerTest.java`（构造器加 ApprovalStore：内存假实现）

- [ ] **Step 1: 改 BrokerService**（注入 `io.custos.engine.approval.ApprovalStore`；queryDb 用 `Decision.effect()` 分流）
```java
// 字段 + 构造器加 ApprovalStore approvals
public BrokerService(TokenService tokens, Pdp pdp, ResourceManager resources,
                     SecretlessQueryExecutor executor, AuditLog audit, ApprovalStore approvals) { ... }

public QueryResult queryDb(QueryIntent intent, String userToken) {
    TokenClaims claims = tokens.verify(userToken);
    String sub = "agent:" + AgentId.parse(claims.subject()).agent();
    String obj = "tool:" + intent.tool();

    // 审批重发：带 approvalId → 校 APPROVED + 未过期 + 一致 + 未 CONSUMED
    if (intent.approvalId() != null && !intent.approvalId().isBlank()) {
        var ap = approvals.get(intent.approvalId()).orElse(null);
        boolean ok = ap != null && ap.status() == io.custos.engine.approval.ApprovalStatus.APPROVED
                && System.currentTimeMillis() < ap.expireAt()
                && ap.agent().equals(sub) && ap.tool().equals(intent.tool()) && ap.resource().equals(intent.resource());
        if (!ok) { record(sub, intent.tool(), obj, "deny", "", "approval invalid/expired/mismatch"); return QueryResult.denied("approval invalid or expired"); }
        approvals.markConsumed(intent.approvalId());
        return issueAndRun(sub, obj, intent, "allow-approved");
    }

    Decision d = pdp.decide(DecisionRequest.of(sub, obj, "read"));
    if (d.effect() == io.custos.authz.Effect.DENY) { record(sub, intent.tool(), obj, "deny", "", d.reason()); return QueryResult.denied(d.reason()); }
    if (d.effect() == io.custos.authz.Effect.REQUIRE_APPROVAL) {
        String id = approvals.create(sub, intent.tool(), intent.resource(), intent.role(), d.risk(), d.reason());
        record(sub, intent.tool(), obj, "require-approval", "", d.reason());
        return QueryResult.pending(id);
    }
    return issueAndRun(sub, obj, intent, "allow");
}

private QueryResult issueAndRun(String sub, String obj, QueryIntent intent, String decision) {
    var engine = resources.require(intent.resource());
    IssuedCred cred = engine.issue(intent.role(), Duration.ofHours(1));
    try {
        List<Map<String,Object>> rows = executor.runReadonly(engine.jdbcUrl(), cred, intent.sql());
        record(sub, intent.tool(), obj, decision, rows.size() + " rows", cred.leaseId());
        return QueryResult.ok(rows);
    } finally {
        engine.revoke(cred.leaseId());
    }
}
```
> 保留 `record(...)` 私有审计方法。import `io.custos.engine.approval.ApprovalStore`。

- [ ] **Step 2: 改三个测试构造器**
  - `BrokerServiceIT`：装 `JimmerApprovalStore(sql)`（已有 sql），传入新构造器；新增审批流测试方法（见 Step 3）。
  - `BrokerAuditWiringTest` / `McpQueryToolServerTest`：加一个内存假 `ApprovalStore`（deny/sealed 路径不触达；但 BrokerService 构造需要它）——内存 Map 实现 create/get/listPending/approve/deny/markConsumed。

- [ ] **Step 3: BrokerServiceIT 审批流 IT** —— `broker()` 工厂加一个用 `AbacPdp` 的变体（`new AbacPdp(casbinPdp, req->55 /*mid risk*/, new DenyApprovalHook(), AbacPolicy(approvalThreshold=50,denyThreshold=90))`），policy 允许 claude-prod。测试：
```java
// 首次：中风险 → PENDING + id
QueryResult p = broker.queryDb(new QueryIntent("db/query_orders","appdb","SELECT COUNT(*) AS n FROM appdb.orders"), tokenFor("claude-prod"));
assertEquals(QueryStatus.PENDING, p.status()); assertNotNull(p.approvalId());
// 运维 approve
approvalStore.approve(p.approvalId(), System.currentTimeMillis()+600000);
// 携 id 重发 → ALLOWED + 真查到行
QueryResult a = broker.queryDb(new QueryIntent("db/query_orders","appdb","read-only","SELECT COUNT(*) AS n FROM appdb.orders", p.approvalId()), tokenFor("claude-prod"));
assertTrue(a.allowed()); assertEquals(2L, ((Number)a.rows().get(0).get("n")).longValue());
// 二次重发（已 CONSUMED）→ denied
QueryResult again = broker.queryDb(new QueryIntent("db/query_orders","appdb","read-only","SELECT 1", p.approvalId()), tokenFor("claude-prod"));
assertFalse(again.allowed());
```
（AbacPolicy 构造/RiskScorer lambda 形状以 `authz` 现有 `AbacPolicy`/`DefaultRiskScorer` 为准，先 Read 确认 approvalThreshold/denyThreshold 字段名。）

- [ ] **Step 4: 跑 broker verify** — Run: `mvn -pl broker -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿（含审批流 IT、BrokerServiceIT 原 allow/deny、BrokerAuditWiringTest、McpQueryToolServerTest）

- [ ] **Step 5: Commit**
```bash
git add broker/
git commit -m "feat(broker): approval flow — pending on REQUIRE_APPROVAL, release on approved retry"
```

---

### Task 5: app — ApprovalController + 装配

**Files:**
- Create: `app/src/main/java/io/custos/app/approval/ApprovalController.java`
- Modify: `app/src/main/java/io/custos/app/operator/OperatorService.java`（assemble 装 JimmerApprovalStore + BrokerService 新构造器；UnsealedContext 加访问器）
- Modify: `app/src/main/java/io/custos/app/engine/UnsealedContext.java`（加 ApprovalStore）
- Modify: `app/src/main/java/io/custos/app/security/AdminTokenFilter.java`（adminPath + `/approvals`）
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（adminTokenFilter urlPatterns + `/approvals/*`）
- Modify: `app/src/main/java/io/custos/app/query/QueryController.java`（body 取 approvalId）
- Test: `app/src/test/java/io/custos/app/ApprovalFlowIT.java`
- Modify: `app/src/test/java/io/custos/app/HostEndToEndIT.java` / `OperatorServiceTest.java`（BrokerService 构造随动——经 assemble，通常无须改测试，确认编译）

- [ ] **Step 1: 改 assemble** —— `OperatorService.assemble()`：建 `JimmerApprovalStore approvals = new JimmerApprovalStore(engine.sql())`；`BrokerService broker = new BrokerService(tokens, pdp, resourceManager, new SecretlessQueryExecutor(), audit, approvals)`；`ctx.set(new UnsealedContext(storage, audit, broker, resourceManager, approvals))`。`UnsealedContext` record 加 `ApprovalStore approvals` 字段 + 访问器。全局搜 `new UnsealedContext(` 改全。

- [ ] **Step 2: 写 ApprovalController**
```java
package io.custos.app.approval;

import io.custos.app.operator.OperatorService;
import io.custos.engine.approval.PendingApproval;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** admin-gated 审批队列。 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {
    private final OperatorService op;
    public ApprovalController(OperatorService op) { this.op = op; }

    @GetMapping
    public List<PendingApproval> pending() { return op.unsealed().approvals().listPending(); }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id) {
        op.unsealed().approvals().approve(id, System.currentTimeMillis() + 15 * 60_000L);  // 15min 有效窗
        return Map.of("id", id, "status", "approved");
    }

    @PostMapping("/{id}/deny")
    public Map<String, Object> deny(@PathVariable String id) {
        op.unsealed().approvals().deny(id);
        return Map.of("id", id, "status", "denied");
    }
}
```

- [ ] **Step 3: AdminTokenFilter + HostConfig** —— AdminTokenFilter 的 adminPath 加 `|| path.startsWith("/approvals")`；HostConfig adminTokenFilter `addUrlPatterns` 加 `"/approvals/*"`。

- [ ] **Step 4: QueryController** —— `new QueryIntent(body.get("tool"), body.get("resource"), body.getOrDefault("role","read-only"), body.get("sql"), body.get("approvalId"))`。

- [ ] **Step 5: 写 ApprovalFlowIT**（Spring Boot Test + Testcontainers MySQL，参照 ResourceControllerIT 装配 + admin token）：init+unseal → 注册 appdb 资源 → 配置一个会触发 REQUIRE_APPROVAL 的策略/风险（若默认 PDP 不触发，本 IT 直接走 ApprovalStore + REST：`GET /approvals`(空)→ 手动 store.create 或经一次中风险 query → `GET /approvals` 列出 → `POST /approvals/{id}/approve`（无 token 401、带 token 200）→ 携 approvalId query_db → allowed）。最小覆盖：`/approvals` 无 token→401；approve 后携 id 重发放行。

- [ ] **Step 6: 跑 app verify** — Run: `mvn -pl app -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿

- [ ] **Step 7: Commit**
```bash
git add app/
git commit -m "feat(app): /approvals REST + ApprovalStore wiring"
```

---

### Task 6: schema 同步 + demo + 全量门禁 + 看板

**Files:**
- Modify: `examples/init/schema.sql`（加 custos_approval，同 Task 1）
- Modify: `examples/demo.md`（审批流 AC：中风险→PENDING→approve→重发放行）
- Modify: `docs/spec/module/M20-approval-closure.md`（status planned→done + subtasks/锚）
- 产物：`docs/cockpit.html` 重渲

- [ ] **Step 1: examples/init/schema.sql** —— 追加 Task 1 的 `CREATE TABLE custos_approval (...)`（容器栈建表）。

- [ ] **Step 2: demo.md** —— 在 AC5（可解释拒绝）后加 AC10「审批闭环」：触发中风险 → query_db 返回 `{"status":"PENDING","approvalId":...}` → `curl GET /approvals`（admin）列出 → `POST /approvals/{id}/approve` → 携 approvalId 重发 query_db → `{"status":"ALLOWED","rows":...}`；`audit verify` 仍 ok。

- [ ] **Step 3: 全量门禁** — Run: `mvn -B clean verify` Expected: BUILD SUCCESS（engine 含 approval 包、broker 审批流、app /approvals 全绿 + 契约回归）

- [ ] **Step 4: M20 看板卡 done** —— `docs/spec/module/M20-approval-closure.md` frontmatter `status: planned`→`done`、`progress: 100`，subtasks 列本计划 6 任务并加 `@code:`/`@docs:` 锚（参照 M15 卡风格）。

- [ ] **Step 5: 重渲 + lint** — Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml && python3 -m docs_cockpit lint --config docs-cockpit.yaml`（期望 0 error/0 warning）。

- [ ] **Step 6: Commit + 合并**
```bash
git add examples/init/schema.sql examples/demo.md docs/spec/module/M20-approval-closure.md docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "docs: M20 approval-closure schema sync + demo AC + dashboard card"
git checkout main && git merge --ff-only impl/m20-approval-closure && git push origin main
```

---

## Self-Review

**1. Spec 覆盖：** §1 决策 D1（异步重发）→Task4 重发逻辑；D2（custos_approval 表）→Task1/2；D3（QueryResult+status）→Task3；D4（携 id 校一致+CONSUMED）→Task4。§2 组件→Task1-5 逐一。§3 数据流四步→Task4（pending/approve/放行/拒）。§4 契约→Task3。§5 数据模型→Task1 schema。§6 测试→各 IT。§7 验收→Task6 门禁。§8 YAGNI（不做 console/不改 AbacPdp/单审批人/单次有效）→遵守。无遗漏。

**2. 占位符扫描：** 新类（ApprovalStatus/PendingApproval/ApprovalRow/ApprovalStore/JimmerApprovalStore/QueryResult/QueryIntent/BrokerService/ApprovalController）给完整代码；IT 装配指明照搬具体现有文件（DefaultLeaseManagerIT/ResourceControllerIT）；AbacPolicy 字段名标注"先 Read 确认"（approvalThreshold/denyThreshold）——这是真实核准动作非占位。

**3. 类型一致：** `ApprovalStore.create(agent,tool,resource,role,risk,reason)→String id` 与 BrokerService 调用一致；`PendingApproval` 字段与 toDomain/ApprovalController 用法一致；`QueryResult(status,rows,denyReason,approvalId)` 与 ok/denied/pending 工厂、McpQueryToolServer switch、BrokerService 一致；`QueryIntent(...,approvalId)` 与 QueryController/McpQueryToolServer/BrokerService 一致；`BrokerService(...,ApprovalStore)` 与 OperatorService.assemble 一致；`UnsealedContext(...,ApprovalStore)` 与 ApprovalController/assemble 一致；`ApprovalStatus{PENDING,APPROVED,DENIED,CONSUMED}` 全程一致。
