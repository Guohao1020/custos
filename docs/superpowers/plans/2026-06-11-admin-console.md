# M16 后台管理控制台（Admin Console）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全自建独立 Vue 3 + Element Plus 控制台 + 支撑只读端点：审计链分页浏览、实时监控、运维动作、资源配置 GUI、审批队列（消费 M20），让安全团队"看得见" custos 运行态。

**Architecture:** engine 加只读查询（AuditLog 分页/计数/决策计数、LeaseManager 列活跃租约）；app 加只读 REST（`GET /audit` 分页、`/leases`、`/monitor/stats`、`/policy`）+ `CorsConfig` + AdminTokenFilter 扩 adminPath，解封装配把 LeaseManager 接进 UnsealedContext；新建独立 `console/`（Vite+Vue3+Element Plus，axios Bearer 拦截器 + 6 视图），compose 加 nginx 服务（多阶段 Dockerfile：node 构建 → nginx 服 dist）。

**Tech Stack:** Java 21 · Maven · Jimmer 查询 API（仿现有 HashChainAuditLog/DefaultLeaseManager）· Spring Boot Web 3.3.2（WebMvcConfigurer CORS）· Testcontainers MySQL 1.19.8（api.version=1.40，`*IT`→failsafe）· Vue 3 + Vite 5 + Element Plus + axios + Vitest · nginx:alpine。

**前置：** 分支 `impl/m16-console`（已建）。spec：`docs/superpowers/specs/2026-06-11-admin-console-design.md`。M20 已合并（`/approvals` + ApprovalStore 在 UnsealedContext）。

**红线：**
- console 不持任何后端密钥，仅持运维填入的 admin token（sessionStorage，刷新即清）；CORS 只放行可配 origin + Authorization 头，不用通配符。
- 资源注册表单的高权限密码：仅随 body 出站，**不回显、不存 localStorage、不打印**。
- 所有新只读端点 admin-gated（无 token→401）；`sensitive_hmac` 已脱敏可安全展示，但审计投影 `AuditEntry` **不含** prev_hash/chain_hash/sensitive_hmac 原文。
- 凭证从不进任何端点返回值。

**约定（踩坑）：**
- PowerShell 下 `mvn` 带点 `-D` 用停止解析：`mvn --% -pl ... -Dtest=...`。
- `*IT` 归 failsafe（`mvn verify` 或 `failsafe:integration-test -Dit.test=...`），不归 surefire（`mvn test -Dtest=` 会判定未运行）。先看现有 IT 怎么跑。
- 看板用 `python3`（不是 python）。
- commit subject 英文 conventional 前缀；body 中文；末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 1: engine — AuditLog 只读查询（分页 / 计数 / 决策计数）

**Files:**
- Create: `engine/src/main/java/io/custos/engine/audit/AuditEntry.java`
- Create: `engine/src/main/java/io/custos/engine/audit/AuditQuery.java`
- Modify: `engine/src/main/java/io/custos/engine/audit/AuditLog.java`（接口加 3 方法）
- Modify: `engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java`（实现）
- Test: `engine/src/test/java/io/custos/engine/audit/AuditQueryIT.java`

- [ ] **Step 1: 写投影与查询条件类**

```java
// AuditEntry.java —— 审计行的只读展示投影（不含 prev_hash/chain_hash/sensitive_hmac 原文）
package io.custos.engine.audit;
/** 审计行只读投影：供 console 浏览。脱敏——无哈希链内部字段。 */
public record AuditEntry(long seq, long ts, String actor, String task, String resource,
                         String action, String decision, String resultDigest) {}
```

```java
// AuditQuery.java —— 分页/过滤条件（null 字段=不过滤；from/to 为 ts 毫秒闭区间）
package io.custos.engine.audit;
/** 审计查询条件。actor/decision 为精确匹配（null=不过滤）；from/to 为 ts 闭区间（null=不限）；page 从 0 起，size>0。 */
public record AuditQuery(String actor, String decision, Long from, Long to, int page, int size) {
    public AuditQuery {
        if (size <= 0) size = 20;
        if (size > 500) size = 500;
        if (page < 0) page = 0;
    }
    public static AuditQuery firstPage() { return new AuditQuery(null, null, null, null, 0, 20); }
}
```

- [ ] **Step 2: 写失败 IT**

先 Read `engine/src/test/java/io/custos/engine/audit/` 下现有的 HashChainAuditLog 测试（找它如何起 Testcontainers MySQL、建 `custos_audit` 表、构造 `HashChainAuditLog(JSqlClient sql, CipherSuite suite, byte[] auditKey)`、append `AuditRecord`）。照搬其样板写 `AuditQueryIT`。`AuditRecord` 的构造形状以现有 append 调用为准（含 actor/task/resource/action/decision/resultDigest/sensitiveRaw 等字段——以真实 `AuditRecord` 定义为准）。

```java
package io.custos.engine.audit;
// 照现有审计 IT 的 import：MySQLContainer、JimmerClients、JSqlClient、CipherSuite(IntlSuite)、Testcontainers、junit
@org.testcontainers.junit.jupiter.Testcontainers
class AuditQueryIT {
    // @Container MySQLContainer<>("mysql:8.0").withUsername("root").withPassword("root")
    // @BeforeEach: 建 custos_audit 表（照现有审计 IT 的建表 SQL）；sql=JimmerClients.of(ds);
    //   log = new HashChainAuditLog(sql, IntlSuite, new byte[32]);
    //   依次 append 6 条（actor 交替 "agent:a"/"agent:b"；decision 交替 "allow"/"deny"/"require-approval"）

    @org.junit.jupiter.api.Test
    void pagedNewestFirst() {
        // append 顺序后，query firstPage(size=3) 应返回最新 3 条，seq 降序
        var page0 = log.query(new AuditQuery(null, null, null, null, 0, 3));
        org.junit.jupiter.api.Assertions.assertEquals(3, page0.size());
        org.junit.jupiter.api.Assertions.assertTrue(page0.get(0).seq() > page0.get(1).seq()); // 降序
        var page1 = log.query(new AuditQuery(null, null, null, null, 1, 3));
        org.junit.jupiter.api.Assertions.assertEquals(3, page1.size());
        org.junit.jupiter.api.Assertions.assertTrue(page0.get(2).seq() > page1.get(0).seq()); // 翻页不重叠
    }

    @org.junit.jupiter.api.Test
    void filterByActorAndDecision() {
        var onlyA = log.query(new AuditQuery("agent:a", null, null, null, 0, 100));
        org.junit.jupiter.api.Assertions.assertTrue(onlyA.stream().allMatch(e -> e.actor().equals("agent:a")));
        var onlyDeny = log.query(new AuditQuery(null, "deny", null, null, 0, 100));
        org.junit.jupiter.api.Assertions.assertTrue(onlyDeny.stream().allMatch(e -> e.decision().equals("deny")));
        org.junit.jupiter.api.Assertions.assertEquals(onlyDeny.size(), log.count(new AuditQuery(null,"deny",null,null,0,100)));
    }

    @org.junit.jupiter.api.Test
    void decisionCountsAllAndRecent() {
        var all = log.decisionCounts(0);          // 0=全量
        org.junit.jupiter.api.Assertions.assertTrue(all.getOrDefault("allow",0L) >= 1);
        var recent = log.decisionCounts(2);        // 仅最近 2 行
        org.junit.jupiter.api.Assertions.assertTrue(recent.values().stream().mapToLong(Long::longValue).sum() <= 2);
    }
}
```

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl engine test-compile failsafe:integration-test -Dit.test=AuditQueryIT -DfailIfNoTests=false`（或 engine 现有审计 IT 的跑法）Expected: 编译失败（query/count/decisionCounts 未定义）

- [ ] **Step 4: 接口加方法**

在 `AuditLog.java` 接口加：
```java
import java.util.List;
import java.util.Map;
// ... 现有 append/verify 之后：
/** 按条件分页查询（seq 降序，最新在前）。 */
List<AuditEntry> query(AuditQuery q);
/** 满足条件的总行数（忽略 page/size）。 */
long count(AuditQuery q);
/** 决策计数：recentWindow<=0 统计全量，>0 仅统计最近 recentWindow 行（按 seq 降序取）。key=decision。 */
Map<String, Long> decisionCounts(int recentWindow);
```

- [ ] **Step 5: HashChainAuditLog 实现**

先 Read `HashChainAuditLog.java` 看它现有的 Jimmer 查询写法（`AuditTable.$`/`AuditRowTable.$` 实际类名、`sql.createQuery(...)` 模式、`AuditRow` 访问器 `seq()/ts()/actor()/task()/resource()/action()/decision()/resultDigest()`）。按实际 Table 类名实现（下面用 `AuditRowTable`，以 APT 生成实际为准）：

```java
// import java.util.List; java.util.Map; java.util.stream.Collectors;
// import org.babyfish.jimmer.sql.ast.Predicate; （按需）

@Override
public List<AuditEntry> query(AuditQuery q) {
    AuditRowTable t = AuditRowTable.$;
    return sql.createQuery(t)
            .whereIf(q.actor() != null, () -> t.actor().eq(q.actor()))
            .whereIf(q.decision() != null, () -> t.decision().eq(q.decision()))
            .whereIf(q.from() != null, () -> t.ts().ge(q.from()))
            .whereIf(q.to() != null, () -> t.ts().le(q.to()))
            .orderBy(t.seq().desc())
            .select(t)
            .limit(q.size(), (long) q.page() * q.size())
            .execute().stream().map(this::toEntry).toList();
}

@Override
public long count(AuditQuery q) {
    AuditRowTable t = AuditRowTable.$;
    return sql.createQuery(t)
            .whereIf(q.actor() != null, () -> t.actor().eq(q.actor()))
            .whereIf(q.decision() != null, () -> t.decision().eq(q.decision()))
            .whereIf(q.from() != null, () -> t.ts().ge(q.from()))
            .whereIf(q.to() != null, () -> t.ts().le(q.to()))
            .select(t.count())
            .execute().get(0);
}

@Override
public Map<String, Long> decisionCounts(int recentWindow) {
    AuditRowTable t = AuditRowTable.$;
    List<AuditRow> rows;
    if (recentWindow <= 0) {
        rows = sql.createQuery(t).select(t).execute();
    } else {
        rows = sql.createQuery(t).orderBy(t.seq().desc()).select(t).limit(recentWindow, 0L).execute();
    }
    return rows.stream()
            .filter(r -> r.decision() != null)
            .collect(Collectors.groupingBy(AuditRow::decision, Collectors.counting()));
}

private AuditEntry toEntry(AuditRow r) {
    return new AuditEntry(r.seq(), r.ts(), r.actor(), r.task(), r.resource(),
            r.action(), r.decision(), r.resultDigest());
}
```
> 注意：Jimmer 的 `limit(limit, offset)` 形参与版本相关——先看现有代码/`jimmer.md` 确认 `limit(int, long)` 还是 `.limit(int).offset(long)`；若 `whereIf` 不可用则改 `where(... ? pred : null)` 或用 `Predicate` 拼装。以编译通过 + IT 绿为准。

- [ ] **Step 6: 跑确认通过** — Run: `mvn --% -pl engine test-compile failsafe:integration-test -Dit.test=AuditQueryIT -DfailIfNoTests=false` Expected: 3 tests PASS（真连 MySQL，非 skip）

- [ ] **Step 7: Commit**
```bash
git add engine/src/main/java/io/custos/engine/audit/AuditEntry.java engine/src/main/java/io/custos/engine/audit/AuditQuery.java engine/src/main/java/io/custos/engine/audit/AuditLog.java engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java engine/src/test/java/io/custos/engine/audit/AuditQueryIT.java
git commit -m "feat(engine): audit read API — paged query, count, decision counts"
```

---

### Task 2: engine — LeaseManager 列活跃租约

**Files:**
- Modify: `engine/src/main/java/io/custos/engine/lease/LeaseManager.java`（接口加 `listActive`）
- Modify: `engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java`（实现）
- Test: `engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java`（加用例）

- [ ] **Step 1: 接口加方法** —— `LeaseManager.java` 加：
```java
import java.util.List;
/** 列出活跃租约（未撤销且未过期）。供运维只读浏览。 */
List<Lease> listActive();
```
（`Lease` 已存在：`record Lease(String leaseId, String resourcePath, long issuedAt, long expireAt)`。`LeaseRow` 有 `revoked: boolean`、`expireAt`。）

- [ ] **Step 2: 在 DefaultLeaseManagerIT 加失败用例**

先 Read `DefaultLeaseManagerIT.java` 看 register/revoke 现有用法与容器装配。加：
```java
@org.junit.jupiter.api.Test
void listActiveExcludesRevokedAndExpired() {
    DefaultLeaseManager m = new DefaultLeaseManager(sql);
    Lease live = m.register("db/appdb", java.time.Duration.ofHours(1), id -> {});
    Lease toRevoke = m.register("db/appdb", java.time.Duration.ofHours(1), id -> {});
    m.revoke(toRevoke.leaseId());
    var active = m.listActive();
    org.junit.jupiter.api.Assertions.assertTrue(active.stream().anyMatch(l -> l.leaseId().equals(live.leaseId())));
    org.junit.jupiter.api.Assertions.assertTrue(active.stream().noneMatch(l -> l.leaseId().equals(toRevoke.leaseId())));
}
```

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl engine test-compile failsafe:integration-test -Dit.test=DefaultLeaseManagerIT -DfailIfNoTests=false` Expected: 编译失败（listActive 未定义）

- [ ] **Step 4: 实现** —— 仿 `DefaultLeaseManager` 现有 `sweepExpired()` 的查询（Read 它确认 `LeaseRowTable.$` 实际类名与 `revoked()/expireAt()` 访问器）：
```java
import java.util.List;
@Override
public List<Lease> listActive() {
    long now = System.currentTimeMillis();
    LeaseRowTable t = LeaseRowTable.$;
    return sql.createQuery(t)
            .where(t.revoked().eq(false))
            .where(t.expireAt().gt(now))
            .orderBy(t.issuedAt().desc())
            .select(t).execute().stream()
            .map(r -> new Lease(r.leaseId(), r.resourcePath(), r.issuedAt(), r.expireAt()))
            .toList();
}
```
> `expireAt` 的单位（毫秒/秒）以 register 写入逻辑为准——与 sweepExpired 比较时用的同一基准；若 sweepExpired 用 `System.currentTimeMillis()` 则此处一致。

- [ ] **Step 5: 跑确认通过** — Run: `mvn --% -pl engine test-compile failsafe:integration-test -Dit.test=DefaultLeaseManagerIT -DfailIfNoTests=false` Expected: 全 PASS（含新用例真跑）

- [ ] **Step 6: Commit**
```bash
git add engine/src/main/java/io/custos/engine/lease/LeaseManager.java engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java engine/src/test/java/io/custos/engine/lease/DefaultLeaseManagerIT.java
git commit -m "feat(engine): LeaseManager.listActive — non-revoked, non-expired leases"
```

---

### Task 3: app — 只读端点（/audit 分页 · /leases · /monitor/stats · /policy）+ 装配接线

**Files:**
- Modify: `app/src/main/java/io/custos/app/engine/UnsealedContext.java`（加 `LeaseManager leases`）
- Modify: `app/src/main/java/io/custos/app/operator/OperatorService.java`（assemble 把 leases 接进 UnsealedContext）
- Modify: `app/src/main/java/io/custos/app/audit/AuditController.java`（加 `GET /audit` 分页）
- Create: `app/src/main/java/io/custos/app/monitor/LeaseController.java`（`GET /leases`）
- Create: `app/src/main/java/io/custos/app/monitor/MonitorController.java`（`GET /monitor/stats`）
- Create: `app/src/main/java/io/custos/app/monitor/MonitorStats.java`
- Modify: `app/src/main/java/io/custos/app/policy/PolicyController.java`（加 `GET /policy`）
- Modify: `app/src/main/java/io/custos/app/security/AdminTokenFilter.java`（adminPath 加 `/leases`、`/monitor`）
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（addUrlPatterns 加 `/leases/*`、`/monitor/*`）
- Test: `app/src/test/java/io/custos/app/ConsoleReadEndpointsIT.java`

- [ ] **Step 1: UnsealedContext + assemble 接线**
  - `UnsealedContext` record 末尾加 `io.custos.engine.lease.LeaseManager leases`。
  - `OperatorService.assemble()`：现有 `DefaultLeaseManager leases = new DefaultLeaseManager(engine.sql())`（行 82）已创建——把它传进 `new UnsealedContext(storage, audit, broker, resourceManager, approvals, leases)`。
  - 全局搜 `new UnsealedContext(` 改全部调用点。

- [ ] **Step 2: AuditController 加 GET /audit**

先 Read 现有 `AuditController.java`（有 `GET /verify`，注入 OperatorService）。加：
```java
import io.custos.engine.audit.AuditEntry;
import io.custos.engine.audit.AuditQuery;
import java.util.List;
import java.util.Map;
// ...
@GetMapping
public Map<String, Object> browse(
        @RequestParam(required = false) String agent,
        @RequestParam(required = false) String decision,
        @RequestParam(required = false) Long from,
        @RequestParam(required = false) Long to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    var audit = op.unsealed().audit();
    AuditQuery q = new AuditQuery(agent, decision, from, to, page, size);
    List<AuditEntry> rows = audit.query(q);
    long total = audit.count(q);
    return Map.of("rows", rows, "total", total, "page", page, "size", size);
}
```
（`op.unsealed().audit()` 返回 `AuditLog`——M16 已给它加 query/count。`op` 字段名以现有为准。）

- [ ] **Step 3: LeaseController**
```java
package io.custos.app.monitor;

import io.custos.app.operator.OperatorService;
import io.custos.engine.lease.Lease;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** admin-gated：活跃租约只读浏览。 */
@RestController
@RequestMapping("/leases")
public class LeaseController {
    private final OperatorService op;
    public LeaseController(OperatorService op) { this.op = op; }

    @GetMapping
    public List<Lease> active() { return op.unsealed().leases().listActive(); }
}
```

- [ ] **Step 4: MonitorStats + MonitorController**
```java
// MonitorStats.java
package io.custos.app.monitor;
import java.util.Map;
/** 监控聚合：seal 态 + 审计总数 + 活跃租约 + 资源数 + 决策计数 + 近窗拒绝率。 */
public record MonitorStats(boolean sealed, int sealThreshold, int sealProgress,
                           long auditTotal, long activeLeases, int resourceCount,
                           Map<String, Long> decisionCounts, double denyRateRecent) {}
```
```java
// MonitorController.java
package io.custos.app.monitor;

import io.custos.app.operator.OperatorService;
import io.custos.engine.audit.AuditQuery;
import io.custos.engine.seal.SealStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** admin-gated：运行态聚合统计。 */
@RestController
@RequestMapping("/monitor")
public class MonitorController {
    private static final int RECENT_WINDOW = 200;
    private final OperatorService op;
    public MonitorController(OperatorService op) { this.op = op; }

    @GetMapping("/stats")
    public MonitorStats stats() {
        SealStatus seal = op.status();
        var ctx = op.unsealed();
        long auditTotal = ctx.audit().count(new AuditQuery(null, null, null, null, 0, 1));
        Map<String, Long> counts = ctx.audit().decisionCounts(0);
        Map<String, Long> recent = ctx.audit().decisionCounts(RECENT_WINDOW);
        long recentTotal = recent.values().stream().mapToLong(Long::longValue).sum();
        long recentDeny = recent.getOrDefault("deny", 0L);
        double denyRate = recentTotal == 0 ? 0.0 : (double) recentDeny / recentTotal;
        return new MonitorStats(seal.sealed(), seal.threshold(), seal.progress(),
                auditTotal, ctx.leases().listActive().size(), ctx.resourceManager().list().size(),
                counts, denyRate);
    }
}
```
> `count(...)` 忽略 page/size，传 `new AuditQuery(null,null,null,null,0,1)` 即全量计数。`op.status()` 返回 `SealStatus(sealed,progress,threshold)`。

- [ ] **Step 5: PolicyController 加 GET /policy**

先 Read `PolicyController.java` 看它怎么拿 `ControlPlane` 与 policy dataId（可能注入 ControlPlane bean + `CustosProperties.nacos().policyDataId()`）。加只读：
```java
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Map;
// ...
@GetMapping
public Map<String, Object> current() {
    String text = controlPlane.get(policyDataId);   // 字段名以现有注入为准
    return Map.of("dataId", policyDataId, "policy", text == null ? "" : text);
}
```

- [ ] **Step 6: AdminTokenFilter + HostConfig 扩展**
  - `AdminTokenFilter.java` adminPath 加 `|| path.startsWith("/leases") || path.startsWith("/monitor")`。
  - `HostConfig.java` `adminTokenFilter()` 的 `addUrlPatterns(...)` 加 `"/leases/*"`、`"/monitor/*"`。

- [ ] **Step 7: 写 ConsoleReadEndpointsIT**

照 `ResourceControllerIT` 模板（`@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` MySQL + `@DynamicPropertySource` 注入 storage-url/空 nacos-server-addr + `FixedTokenConfig` 固定 admin token + 自建永不抛异常的 RestTemplate）。`@BeforeAll seed()` 建表要含 `custos_audit`、`custos_lease`、`custos_storage`、`custos_seal_config`、`custos_approval`。测试：
```java
// 解封后：
// 1) 无 token GET /audit、/leases、/monitor/stats → 401
// 2) 带 admin token GET /audit → 200，body 有 rows/total/page/size 字段
// 3) 带 admin token GET /leases → 200，返回 JSON 数组（解封初无租约则空数组）
// 4) 带 admin token GET /monitor/stats → 200，sealed=false、resourceCount>=0、decisionCounts 是对象
// 5) 经一次 query_db（注册 appdb 资源后，照 ResourceControllerIT 注册流程）产生审计行 → GET /audit total 增大、/monitor decisionCounts 含 allow 或 deny
```
最小必须覆盖：①（401）与 ②③④（200 + 字段）。⑤ 若装配成本高可简化为「直接 append 一条审计后 /audit total>=1」——但优先经真实 query_db 走通端到端。

- [ ] **Step 8: 跑 app verify** — Run: `mvn --% -pl app -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿（ConsoleReadEndpointsIT + 现有 ResourceControllerIT/ApprovalFlowIT/HostEndToEndIT 回归）

- [ ] **Step 9: Commit**
```bash
git add app/src/main/java/io/custos/app/ app/src/test/java/io/custos/app/ConsoleReadEndpointsIT.java
git commit -m "feat(app): read endpoints — paged /audit, /leases, /monitor/stats, GET /policy"
```

---

### Task 4: app — CORS 配置（放行可配 console 源）

**Files:**
- Modify: `app/src/main/java/io/custos/app/config/CustosProperties.java`（加 `Console` 嵌套）
- Create: `app/src/main/java/io/custos/app/config/CorsConfig.java`
- Test: `app/src/test/java/io/custos/app/CorsConfigIT.java`

- [ ] **Step 1: CustosProperties 加 Console**

Read `CustosProperties.java`（`@ConfigurationProperties(prefix="custos")`，已有 Engine/Nacos/Identity/Broker 嵌套 + getters/setters）。仿现有嵌套类加：
```java
private Console console = new Console();
public Console getConsole() { return console; }
public void setConsole(Console console) { this.console = console; }

public static class Console {
    /** 允许跨域的 console 源（逗号分隔多个；默认 dev 的 Vite 5173 + compose 的 3000）。 */
    private String origin = "http://localhost:5173,http://localhost:3000";
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
```

- [ ] **Step 2: 写失败 IT**

照 `ResourceControllerIT` 模板（可不解封——CORS 是 filter 层，预检不进 controller）。`@DynamicPropertySource` 注入 `custos.console.origin=http://localhost:5173`。测试：
```java
// OPTIONS 预检：Origin: http://localhost:5173 + Access-Control-Request-Method: GET
//   → 200/204，响应头 Access-Control-Allow-Origin == http://localhost:5173
//   且 Access-Control-Allow-Headers 含 authorization
// 反例：Origin: http://evil.example → 响应不带 Access-Control-Allow-Origin（或非该源）
HttpHeaders h = new HttpHeaders();
h.setOrigin("http://localhost:5173");
h.set("Access-Control-Request-Method", "GET");
ResponseEntity<Void> r = rest.exchange(base + "/monitor/stats", HttpMethod.OPTIONS, new HttpEntity<>(h), Void.class);
org.junit.jupiter.api.Assertions.assertEquals("http://localhost:5173", r.getHeaders().getAccessControlAllowOrigin());
```

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl app test-compile failsafe:integration-test -Dit.test=CorsConfigIT -DfailIfNoTests=false` Expected: 预检无 CORS 头 → 断言失败

- [ ] **Step 4: 写 CorsConfig**
```java
package io.custos.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 放行可配 console 源 + Authorization 头（不用通配符）。 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] origins;
    public CorsConfig(CustosProperties props) {
        this.origins = props.getConsole().getOrigin().split("\\s*,\\s*");
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
```
> 注意：CORS 预检（OPTIONS）必须能在 AdminTokenFilter 之前放行——Spring MVC 的 CorsFilter/预检处理默认先于自定义 filter 链对 OPTIONS 短路。若 AdminTokenFilter 把 OPTIONS 也拦成 401，需让 AdminTokenFilter 对 `OPTIONS` 方法直接放行（`if ("OPTIONS".equalsIgnoreCase(req.getMethod())) { chain.doFilter(...); return; }`）。在 Step 4 同时给 AdminTokenFilter 加这个 OPTIONS 放行（如 IT 失败定位到此）。

- [ ] **Step 5: 跑确认通过** — Run: `mvn --% -pl app test-compile failsafe:integration-test -Dit.test=CorsConfigIT -DfailIfNoTests=false` Expected: PASS

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/io/custos/app/config/CustosProperties.java app/src/main/java/io/custos/app/config/CorsConfig.java app/src/main/java/io/custos/app/security/AdminTokenFilter.java app/src/test/java/io/custos/app/CorsConfigIT.java
git commit -m "feat(app): CORS config allowing configured console origin"
```

---

### Task 5: console/ — Vue 3 + Element Plus 控制台工程

**Files（全部 Create，在仓库根 `console/`）:**
- `console/package.json`、`console/vite.config.ts`、`console/index.html`、`console/.gitignore`、`console/tsconfig.json`
- `console/src/main.ts`、`console/src/App.vue`
- `console/src/api/client.ts`
- `console/src/router/index.ts`
- `console/src/views/{LoginView,AuditView,MonitorView,OperatorView,ResourceView,ApprovalView}.vue`
- `console/src/api/__tests__/client.spec.ts`、`console/src/views/__tests__/ResourceView.spec.ts`

> Node 可用性：本任务先 `node -v`/`npm -v` 确认。若本机有 Node，跑 `npm install && npm run test:unit` 让 Vitest 真绿；若无 Node，仍创建全部工程文件（`docker compose` 的多阶段构建会在容器内 `npm ci && npm run build`），并在报告里注明本机未跑 Vitest、构建验证留给 Task 6 的 compose。

- [ ] **Step 1: 工程骨架**

`console/package.json`:
```json
{
  "name": "custos-console",
  "version": "0.6.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test:unit": "vitest run"
  },
  "dependencies": {
    "axios": "^1.7.2",
    "element-plus": "^2.7.6",
    "vue": "^3.4.31",
    "vue-router": "^4.4.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.5",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^24.1.0",
    "typescript": "^5.4.5",
    "vite": "^5.3.3",
    "vitest": "^1.6.0",
    "vue-tsc": "^2.0.26"
  }
}
```

`console/vite.config.ts`:
```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173 },
  test: { environment: 'jsdom', globals: true },
})
```

`console/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "jsx": "preserve",
    "types": ["vitest/globals", "node"],
    "lib": ["ES2020", "DOM", "DOM.Iterable"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
```

`console/index.html`:
```html
<!doctype html>
<html lang="zh-CN">
  <head><meta charset="UTF-8" /><title>Custos Console</title></head>
  <body><div id="app"></div><script type="module" src="/src/main.ts"></script></body>
</html>
```

`console/.gitignore`:
```
node_modules
dist
*.log
```

- [ ] **Step 2: api client（axios Bearer 拦截器 + 401 处理）**

`console/src/api/client.ts`:
```ts
import axios, { type AxiosInstance } from 'axios'

const TOKEN_KEY = 'custos_admin_token'
// baseURL：构建期可由 VITE_API_BASE 覆盖；默认同源（nginx 反代）或 dev 直连
const baseURL = import.meta.env.VITE_API_BASE ?? ''

export function getToken(): string | null { return sessionStorage.getItem(TOKEN_KEY) }
export function setToken(t: string): void { sessionStorage.setItem(TOKEN_KEY, t) }
export function clearToken(): void { sessionStorage.removeItem(TOKEN_KEY) }

export function createClient(onUnauthorized: () => void): AxiosInstance {
  const c = axios.create({ baseURL })
  c.interceptors.request.use((cfg) => {
    const t = getToken()
    if (t) cfg.headers.Authorization = `Bearer ${t}`
    return cfg
  })
  c.interceptors.response.use(
    (r) => r,
    (err) => {
      if (err?.response?.status === 401) { clearToken(); onUnauthorized() }
      return Promise.reject(err)
    },
  )
  return c
}
```

- [ ] **Step 3: router（带登录守卫）**

`console/src/router/index.ts`:
```ts
import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api/client'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', redirect: '/monitor' },
  { path: '/monitor', component: () => import('../views/MonitorView.vue') },
  { path: '/audit', component: () => import('../views/AuditView.vue') },
  { path: '/operator', component: () => import('../views/OperatorView.vue') },
  { path: '/resources', component: () => import('../views/ResourceView.vue') },
  { path: '/approvals', component: () => import('../views/ApprovalView.vue') },
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) return '/login'
})
export default router
```

`console/src/main.ts`:
```ts
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App).use(ElementPlus).use(router).mount('#app')
```

`console/src/App.vue`:
```vue
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { clearToken, getToken } from './api/client'
const route = useRoute(); const router = useRouter()
function logout() { clearToken(); router.push('/login') }
</script>
<template>
  <el-container style="height:100vh">
    <el-aside width="200px" v-if="route.path !== '/login'">
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/monitor">实时监控</el-menu-item>
        <el-menu-item index="/audit">审计浏览</el-menu-item>
        <el-menu-item index="/operator">运维动作</el-menu-item>
        <el-menu-item index="/resources">资源配置</el-menu-item>
        <el-menu-item index="/approvals">审批队列</el-menu-item>
      </el-menu>
      <el-button v-if="getToken()" @click="logout" text>退出登录</el-button>
    </el-aside>
    <el-main><router-view /></el-main>
  </el-container>
</template>
```

- [ ] **Step 4: 6 视图**

`console/src/views/LoginView.vue`（填 token → sessionStorage → 跳监控）:
```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setToken } from '../api/client'
const token = ref(''); const router = useRouter()
function login() { if (token.value.trim()) { setToken(token.value.trim()); router.push('/monitor') } }
</script>
<template>
  <el-card style="max-width:420px;margin:80px auto">
    <h2>Custos 控制台登录</h2>
    <el-input v-model="token" type="password" placeholder="填入 admin token" show-password @keyup.enter="login" />
    <el-button type="primary" style="margin-top:12px" @click="login">登录</el-button>
    <p style="color:#888;font-size:12px">token 仅存于 sessionStorage，刷新即清。</p>
  </el-card>
</template>
```

`console/src/views/MonitorView.vue`（轮询 /monitor/stats）:
```vue
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const s = ref<any>(null); let timer: any
async function load() { try { s.value = (await api.get('/monitor/stats')).data } catch {} }
onMounted(() => { load(); timer = setInterval(load, 5000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <h2>实时监控</h2>
  <el-row :gutter="16" v-if="s">
    <el-col :span="6"><el-card>封印态<h1>{{ s.sealed ? 'SEALED' : 'UNSEALED' }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>活跃租约<h1>{{ s.activeLeases }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>资源数<h1>{{ s.resourceCount }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>审计总数<h1>{{ s.auditTotal }}</h1></el-card></el-col>
    <el-col :span="12"><el-card>决策计数<pre>{{ s.decisionCounts }}</pre></el-card></el-col>
    <el-col :span="12"><el-card>近窗拒绝率<h1>{{ (s.denyRateRecent * 100).toFixed(1) }}%</h1></el-card></el-col>
  </el-row>
</template>
```

`console/src/views/AuditView.vue`（表格 + 过滤 + 分页 + 链完整性徽章）:
```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const rows = ref<any[]>([]); const total = ref(0); const page = ref(0); const size = 20
const agent = ref(''); const decision = ref(''); const chain = ref<any>(null)
async function load() {
  const params: any = { page: page.value, size }
  if (agent.value) params.agent = agent.value
  if (decision.value) params.decision = decision.value
  const r = (await api.get('/audit', { params })).data
  rows.value = r.rows; total.value = r.total
}
async function verify() { chain.value = (await api.get('/audit/verify')).data }
onMounted(() => { load(); verify() })
</script>
<template>
  <h2>审计浏览
    <el-tag v-if="chain" :type="chain.ok ? 'success' : 'danger'">
      {{ chain.ok ? '链完整' : '断链 @seq=' + chain.brokenAtSeq }}
    </el-tag>
  </h2>
  <el-input v-model="agent" placeholder="agent 过滤" style="width:200px" @keyup.enter="page=0;load()" />
  <el-input v-model="decision" placeholder="decision 过滤" style="width:200px" @keyup.enter="page=0;load()" />
  <el-button @click="page=0;load()">查询</el-button>
  <el-table :data="rows" style="margin-top:12px">
    <el-table-column prop="seq" label="seq" width="80" />
    <el-table-column prop="ts" label="时间" />
    <el-table-column prop="actor" label="actor" />
    <el-table-column prop="decision" label="决策" />
    <el-table-column prop="resource" label="资源" />
    <el-table-column prop="resultDigest" label="result" />
  </el-table>
  <el-pagination layout="prev, pager, next" :total="total" :page-size="size"
    @current-change="(p:number)=>{page=p-1;load()}" style="margin-top:12px" />
</template>
```

`console/src/views/OperatorView.vue`（解封逐片 + 密封）:
```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const status = ref<any>(null); const share = ref('')
async function load() { status.value = (await api.get('/operator/status')).data }
async function unseal() { status.value = (await api.post('/operator/unseal', { share: share.value })).data; share.value = '' }
async function seal() { await api.post('/operator/seal'); load() }
onMounted(load)
</script>
<template>
  <h2>运维动作</h2>
  <el-card v-if="status">
    <p>封印态：{{ status.sealed ? 'SEALED' : 'UNSEALED' }} · 进度 {{ status.progress }}/{{ status.threshold }}</p>
    <el-input v-model="share" placeholder="提交一片解封分片(base64)" style="width:420px" />
    <el-button type="primary" @click="unseal">提交分片</el-button>
    <el-button @click="seal" :disabled="status.sealed">密封</el-button>
    <p style="color:#888">轮换主密钥：REST 未备，roadmap。</p>
  </el-card>
</template>
```
> `/operator/unseal` 的 body 字段名（`share`/`shareB64`）以现有 OperatorController 为准——先 Read 确认，对齐。

`console/src/views/ResourceView.vue`（列表 + 注册表单密码不回显 + rotate/删除）:
```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const names = ref<string[]>([])
const form = reactive({ name: '', type: 'db.relational', dialect: 'mysql', jdbcUrl: '', adminUsername: '', adminPassword: '' })
async function load() { names.value = (await api.get('/resources')).data }
async function register() {
  await api.post('/resources', { ...form })
  form.adminPassword = ''               // 提交后立即清空密码，不回显
  load()
}
async function rotate(n: string) { await api.post(`/resources/${n}/rotate-admin`) }
async function remove(n: string) { await api.delete(`/resources/${n}`); load() }
onMounted(load)
</script>
<template>
  <h2>资源配置</h2>
  <el-table :data="names.map(n=>({name:n}))">
    <el-table-column prop="name" label="资源名" />
    <el-table-column label="操作">
      <template #default="{ row }">
        <el-button size="small" @click="rotate(row.name)">轮换 admin</el-button>
        <el-button size="small" type="danger" @click="remove(row.name)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-card style="margin-top:16px">
    <h3>注册资源</h3>
    <el-input v-model="form.name" placeholder="name" />
    <el-input v-model="form.jdbcUrl" placeholder="jdbcUrl" />
    <el-input v-model="form.adminUsername" placeholder="adminUsername" />
    <el-input v-model="form.adminPassword" type="password" show-password placeholder="adminPassword(高权限,不回显)" />
    <el-button type="primary" @click="register">注册</el-button>
  </el-card>
</template>
```
> 注册 body 字段名/结构以 M15 `ResourceController` POST 实际接收为准——先 Read 确认（type/dialect/roles 等），对齐表单字段。

`console/src/views/ApprovalView.vue`（pending 表 + approve/deny；M20 已就绪）:
```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const rows = ref<any[]>([])
async function load() { rows.value = (await api.get('/approvals')).data }
async function approve(id: string) { await api.post(`/approvals/${id}/approve`); load() }
async function deny(id: string) { await api.post(`/approvals/${id}/deny`); load() }
onMounted(load)
</script>
<template>
  <h2>审批队列</h2>
  <el-table :data="rows">
    <el-table-column prop="id" label="id" />
    <el-table-column prop="agent" label="agent" />
    <el-table-column prop="tool" label="工具" />
    <el-table-column prop="resource" label="资源" />
    <el-table-column prop="risk" label="风险" />
    <el-table-column prop="reason" label="原因" />
    <el-table-column label="操作">
      <template #default="{ row }">
        <el-button size="small" type="success" @click="approve(row.id)">批准</el-button>
        <el-button size="small" type="danger" @click="deny(row.id)">拒绝</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
```

- [ ] **Step 5: Vitest 单测**

`console/src/api/__tests__/client.spec.ts`:
```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createClient, setToken, getToken, clearToken } from '../client'

describe('api client', () => {
  beforeEach(() => { sessionStorage.clear() })

  it('injects Bearer token into request', async () => {
    setToken('tok-123')
    const api = createClient(() => {})
    const cfg = await (api.interceptors.request as any).handlers[0].fulfilled({ headers: {} })
    expect(cfg.headers.Authorization).toBe('Bearer tok-123')
  })

  it('clears token and calls onUnauthorized on 401', () => {
    setToken('tok-x')
    const onUnauth = vi.fn()
    const api = createClient(onUnauth)
    const rejected = (api.interceptors.response as any).handlers[0].rejected
    return rejected({ response: { status: 401 } }).catch(() => {
      expect(onUnauth).toHaveBeenCalled()
      expect(getToken()).toBeNull()
    })
  })

  it('token round-trips through sessionStorage', () => {
    setToken('abc'); expect(getToken()).toBe('abc'); clearToken(); expect(getToken()).toBeNull()
  })
})
```

`console/src/views/__tests__/ResourceView.spec.ts`（密码提交后清空、不回显）:
```ts
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../../api/client', () => ({
  createClient: () => ({
    get: vi.fn().mockResolvedValue({ data: [] }),
    post: vi.fn().mockResolvedValue({ data: {} }),
    delete: vi.fn().mockResolvedValue({ data: {} }),
  }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import ResourceView from '../ResourceView.vue'

describe('ResourceView 密码不回显', () => {
  it('注册后 adminPassword 被清空', async () => {
    const wrapper = mount(ResourceView, { global: { stubs: { 'el-table': true, 'el-table-column': true, 'el-card': true, 'el-input': true, 'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' } } } })
    const vm: any = wrapper.vm
    vm.form.adminPassword = 'super-secret'
    await vm.register()
    expect(vm.form.adminPassword).toBe('')   // 提交后立即清空
  })
})
```
> 若 stub 形状导致 mount 失败，简化为直接测 `register()` 逻辑（导入组件 setup 暴露的 form/register）或退化为纯函数测试；核心断言：**提交后 adminPassword === ''**。Vitest 真跑（有 Node 时）。

- [ ] **Step 6: 跑 Vitest（若本机有 Node）** — Run: `cd console && npm install && npm run test:unit`（PowerShell 下分两条：`cd console; npm install; npm run test:unit`）Expected: 全绿。无 Node → 跳过并在报告注明，文件齐备留给 Task 6 compose 容器内构建。

- [ ] **Step 7: Commit**
```bash
git add console/
git commit -m "feat(console): Vue3 + Element Plus admin console — 6 views + axios client"
```

---

### Task 6: compose console 服务 + demo + 全量门禁 + 看板 + 合并

**Files:**
- Create: `console/Dockerfile`、`console/nginx.conf`
- Modify: `examples/docker-compose.yml`（加 console 服务）
- Modify: `examples/demo.md`（console AC）
- Modify: `docs/spec/module/M16-admin-console.md`（status planned→done + subtasks/锚）
- 产物：`docs/cockpit.html` 重渲

- [ ] **Step 1: console 多阶段 Dockerfile**

`console/Dockerfile`（node 构建 → nginx 服，宿主无需 Node）:
```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```
> 若需 Aliyun npm 镜像加速：`RUN npm config set registry https://registry.npmmirror.com && npm install ...`（与项目 Dockerfile 默认国内镜像姿态一致；可加 `ARG NPM_MIRROR`）。

`console/nginx.conf`（服 SPA + 反代 admin API 到 custos，避免跨域）:
```nginx
server {
    listen 80;
    location /api/ {
        proxy_pass http://custos:8080/;
        proxy_set_header Host $host;
    }
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
}
```
> 经 nginx `/api/` 反代时，console 构建用 `VITE_API_BASE=/api`（同源，免 CORS）。compose 里给 build arg 或 console 直接打 `VITE_API_BASE=/api`。CORS 配置（Task 4）仍保留供 dev（Vite 5173 直连 8080）用。

- [ ] **Step 2: docker-compose 加 console 服务**

Read `examples/docker-compose.yml`（现有 mysql/nacos/nacos-init/custos，custos 服务名用于 nginx 反代 host）。在 custos 之后加：
```yaml
  console:
    build:
      context: ../console
      args:
        VITE_API_BASE: /api
    ports:
      - "3000:80"
    depends_on:
      - custos
```
（缩进对齐现有 services；`build.context` 相对 compose 文件位置指向 `console/`。）

- [ ] **Step 3: demo.md console 段**

在 demo.md 末尾加「后台控制台（M16）」：`docker compose up` 后浏览器开 `http://localhost:3000` → 登录页填 admin token（compose 的 `CUSTOS_ADMIN_TOKEN` 值）→ 监控卡显示 seal/租约/资源/决策计数 → 审计浏览可分页过滤 + 链完整性徽章 → 资源页注册/轮换/删（密码不回显）→ 审批队列 approve/deny（接 M20）。说明 console 不持后端密钥、token 仅 sessionStorage。

- [ ] **Step 4: 全量门禁** — Run: `mvn -B clean verify` Expected: BUILD SUCCESS（engine 审计/租约只读 + app 端点/CORS + 全回归绿）。console 不进 Maven reactor，不影响门禁；若本机有 Node 另跑 `cd console; npm run test:unit` 确认前端绿。

- [ ] **Step 5: M16 看板卡 done**

Read `docs/spec/module/M16-admin-console.md`（当前 planned）+ 一张 done 卡学风格。改：`status: planned→done`、`progress→100`；加 `subtasks:` 列本计划 6 task（M16-S1..S6）+ `done:true` + `code:` 锚指真实文件 + `docs:` 锚指 spec（用 `path#子串`，子串须命中 spec 的 `## ` 标题行——先 Read spec 实际 heading）。建议锚：
- S1 审计只读 API → code engine/.../audit/{AuditEntry,AuditQuery}.java + HashChainAuditLog.java；doc spec#只读端点（确认实际标题）。
- S2 租约只读 → code engine/.../lease/LeaseManager.java。
- S3 只读端点 → code app/.../monitor/MonitorController.java + app/.../audit/AuditController.java；doc spec#只读端点。
- S4 CORS → code app/.../config/CorsConfig.java；doc spec#鉴权与安全。
- S5 console 工程 → code console/src/api/client.ts + console/src/router/index.ts；doc spec#console-面板（确认实际标题）。
- S6 compose+demo → code console/Dockerfile + examples/docker-compose.yml；doc examples/demo.md。
> M16 卡 `depends_on: [M20]` 已存在——保留。写完锚后用 state.json 验证无 `heading not found`。

- [ ] **Step 6: 重渲 + lint** — Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml` 然后 `python3 -m docs_cockpit lint --config docs-cockpit.yaml` Expected: 0 error / 0 warning；state.json M16 doc_anchors 无 `heading not found`。

- [ ] **Step 7: Commit + 合并 main + push**
```bash
git add console/Dockerfile console/nginx.conf examples/docker-compose.yml examples/demo.md docs/spec/module/M16-admin-console.md docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "docs: M16 admin console — compose service + demo + dashboard card"
git checkout main
git merge --ff-only impl/m16-console
git push origin main
```
> FF 前确认 main 未分叉；`--ff-only` 失败说明 main 有新提交，停下报告别强推。

---

## Self-Review

**1. Spec 覆盖：** spec §3 五端点 → `GET /audit`(Task3-S2)、`/audit/verify`(已存在,console 直用)、`/leases`(Task2+Task3-S3)、`/monitor/stats`(Task3-S4)、`GET /policy`(Task3-S5)；§2 CORS → Task4；§4 六视图（登录/审计/监控/运维/资源/审批）→ Task5 六 `.vue`；§5 鉴权（token sessionStorage + Bearer 拦截 + 401 回登录 + 密码不回显 + CORS 不通配）→ Task5 client.ts + ResourceView + Task4 CorsConfig；§6 测试（后端 IT + 前端 Vitest）→ Task3/4 IT + Task5 spec.ts；§7 交付物（console/ + 后端端点 + AdminTokenFilter 扩 + compose console 服务 + 看板卡）→ Task3/5/6；§8 验收 → Task6 门禁；§9 YAGNI（审批后端=M20 已做、metrics=M17 留、不做 SSO、不打进 app jar）→ 遵守（console 独立工程 + nginx）。

**2. 占位符扫描：** 后端类（AuditEntry/AuditQuery/MonitorStats/CorsConfig/LeaseController/MonitorController）给完整代码；前端给完整 package.json/vite/tsconfig/client/router/main/App + 6 视图 + 2 Vitest。需"先 Read 确认"处均为真实核准动作（Jimmer limit 形参、AuditRow/LeaseRow Table 类名与访问器、`/operator/unseal` body 字段名、ResourceController POST body 形状、PolicyController 的 ControlPlane 注入字段名），非占位。

**3. 类型一致：** `AuditQuery(actor,decision,from,to,page,size)` 与 AuditController 构造、HashChainAuditLog query/count 一致；`AuditEntry(seq,ts,actor,task,resource,action,decision,resultDigest)` 与 toEntry、AuditView 列一致；`decisionCounts(int)` 与 MonitorController 两次调用一致；`Lease(leaseId,resourcePath,issuedAt,expireAt)` 与 listActive 映射、LeaseController、ApprovalView 无关；`MonitorStats(sealed,sealThreshold,sealProgress,auditTotal,activeLeases,resourceCount,decisionCounts,denyRateRecent)` 与 MonitorController 构造、MonitorView 字段一致；`UnsealedContext(storage,audit,broker,resourceManager,approvals,leases)` 与 assemble、LeaseController/MonitorController 的 `.leases()` 一致；CORS origin 配置 `custos.console.origin` 与 CustosProperties.Console、CorsConfig、CorsConfigIT 注入一致；前端 `createClient(onUnauthorized)`/`getToken`/`setToken`/`clearToken` 跨 client.ts/router/各视图/client.spec 一致。
