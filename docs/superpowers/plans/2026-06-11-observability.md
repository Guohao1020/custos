# M17 可观测性（Observability）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 custos 装生产级指标:broker 经框架无关的 `BrokerMetrics` SPI 埋点,app 用 Micrometer+Actuator 暴露 admin-gated `/actuator/prometheus`,compose 预置 Prometheus+Grafana(Custos 仪表盘)。保留 M16 的 `/monitor/stats`,不做 tracing。

**Architecture:** broker 加 `BrokerMetrics` 接口 + `NOOP`,`BrokerService` 构造器加 `metrics` 参并在 queryDb/issueAndRun 埋 counter/timer(broker 仍不依赖 Micrometer/Spring);app 加 actuator+micrometer 依赖、`MicrometerBrokerMetrics`(implements `BrokerMetrics`)、`MetricsConfig`(注册 bean + 4 gauge 读 OperatorService)、`OperatorService` 注入 metrics 并传进 BrokerService、AdminTokenFilter 门控 `/actuator/prometheus`(放行 health);compose 加 prometheus+grafana 服务 + 配置 + 仪表盘 JSON。

**Tech Stack:** Java 21 · Maven · Spring Boot Actuator 3.3.2 + micrometer-registry-prometheus 1.13.2(Spring Boot 3.3.2 对齐) · Testcontainers MySQL 1.19.8(api.version=1.40,`*IT`→failsafe) · prom/prometheus + grafana/grafana(compose)。

**前置:** 分支 `impl/m17-observability`(已建,spec 已 commit)。spec:`docs/superpowers/specs/2026-06-11-observability-design.md`。M16/M20 已合并。BrokerService 现为 6 参构造(tokens,pdp,resources,executor,audit,approvals),OperatorService:90 构造它、OperatorService:37 构造器 `(EngineBootstrap, TokenService, Pdp)`。

**红线:**
- 指标 tag 仅用有界枚举(decision/action),**绝不**用 agent/resource/SQL/token 作 label;指标值只是计数/耗时,从不含凭证。
- broker **不引入** Micrometer/Spring 依赖(SPI + NOOP 保持框架无关)。
- `/actuator/prometheus` admin-gated;`/actuator/health` 开放且 `show-details=never`(不泄 seal 态)。除 health/prometheus 外不暴露任何 actuator 端点。

**约定(踩坑):** PowerShell `mvn` 带点 `-D` 用 `mvn --%`;`*IT` 归 failsafe;看板用 `python3`;commit subject 英文 conventional,body 中文,末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 1: broker — BrokerMetrics SPI + BrokerService 埋点

**Files:**
- Create: `broker/src/main/java/io/custos/broker/BrokerMetrics.java`
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`（构造器加 metrics + queryDb/issueAndRun 埋点）
- Test: `broker/src/test/java/io/custos/broker/BrokerMetricsTest.java`
- Modify: 现有 broker 测试构造 BrokerService 处（BrokerServiceIT / BrokerAuditWiringTest / McpQueryToolServerTest）传 `BrokerMetrics.NOOP` 或 capturing

- [ ] **Step 1: 写 BrokerMetrics SPI**
```java
package io.custos.broker;

import java.time.Duration;

/** 经纪层指标埋点 SPI。broker 保持框架无关——app 提供 Micrometer 实现注入,缺省 NOOP。
 *  实现须线程安全(BrokerService 可并发调用)。 */
public interface BrokerMetrics {
    /** decision ∈ {allow, deny, require-approval, allow-approved}。 */
    void recordDecision(String decision);
    /** action ∈ {created, approved, denied, consumed}。 */
    void recordApproval(String action);
    void recordCredentialIssued();
    void recordCredentialRevoked();
    void recordQueryDuration(Duration d);
    void recordPdpDecisionDuration(Duration d);
    void recordCredentialIssueDuration(Duration d);
    void recordCredentialRevokeDuration(Duration d);

    /** 空实现:不采集(测试/未装配 Micrometer 时用)。 */
    BrokerMetrics NOOP = new BrokerMetrics() {
        public void recordDecision(String decision) {}
        public void recordApproval(String action) {}
        public void recordCredentialIssued() {}
        public void recordCredentialRevoked() {}
        public void recordQueryDuration(Duration d) {}
        public void recordPdpDecisionDuration(Duration d) {}
        public void recordCredentialIssueDuration(Duration d) {}
        public void recordCredentialRevokeDuration(Duration d) {}
    };
}
```

- [ ] **Step 2: 写失败单测 BrokerMetricsTest**

用一个 capturing fake `BrokerMetrics`(累计各 decision/action 次数 + 计时调用次数),复用 BrokerServiceIT 的容器装配跑各路径。**先 Read `BrokerServiceIT.java`** 看 broker()/brokerRequiringApproval() 工厂、tokenFor、种子资源(appdb/db/query_orders、orders 2 行),照搬装配但注入 capturing metrics。结构:
```java
package io.custos.broker;
// 容器/装配 import 照搬 BrokerServiceIT；junit assertions
class BrokerMetricsTest {
    // 内部 capturing 实现
    static final class Capturing implements BrokerMetrics {
        final java.util.Map<String,Integer> decisions = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String,Integer> approvals = new java.util.concurrent.ConcurrentHashMap<>();
        int issued, revoked, queryTimed, pdpTimed, issueTimed, revokeTimed;
        public void recordDecision(String d){decisions.merge(d,1,Integer::sum);}
        public void recordApproval(String a){approvals.merge(a,1,Integer::sum);}
        public void recordCredentialIssued(){issued++;}
        public void recordCredentialRevoked(){revoked++;}
        public void recordQueryDuration(java.time.Duration d){queryTimed++;}
        public void recordPdpDecisionDuration(java.time.Duration d){pdpTimed++;}
        public void recordCredentialIssueDuration(java.time.Duration d){issueTimed++;}
        public void recordCredentialRevokeDuration(java.time.Duration d){revokeTimed++;}
    }
    // @Test allow 路径:queryDb 一次 allow → decisions["allow"]==1, issued==1, revoked==1, queryTimed>=1, pdpTimed>=1
    // @Test deny 路径:被拒 → decisions["deny"]==1, issued==0
    // @Test 审批路径:中风险首次 → decisions["require-approval"]==1 且 approvals["created"]==1;
    //        approve 后携 id 重发 → decisions["allow-approved"]==1 且 approvals["consumed"]==1
}
```
> 注意类名 `BrokerMetricsTest`(不带 IT)若被 surefire 跑而它需要容器,可能因 Testcontainers 启动在 surefire 阶段——BrokerServiceIT 是 `*IT` 走 failsafe。本测试若依赖容器,**命名为 `BrokerMetricsIT`** 走 failsafe 更稳(与 BrokerServiceIT 一致)。按 BrokerServiceIT 的实际跑法决定命名,确保真跑非 skip。

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl broker -am test-compile failsafe:integration-test -Dit.test=BrokerMetricsIT -DfailIfNoTests=false`（或按命名/现有跑法）Expected: 编译失败(BrokerMetrics 未注入 BrokerService)

- [ ] **Step 4: BrokerService 加 metrics 字段 + 埋点**

构造器(43-51 行)加第 7 参:
```java
private final BrokerMetrics metrics;

public BrokerService(TokenService tokens, Pdp pdp, ResourceManager resources,
                     SecretlessQueryExecutor executor, AuditLog audit, ApprovalStore approvals,
                     BrokerMetrics metrics) {
    this.tokens = tokens; this.pdp = pdp; this.resources = resources;
    this.executor = executor; this.audit = audit; this.approvals = approvals;
    this.metrics = metrics == null ? BrokerMetrics.NOOP : metrics;
}
```
queryDb 埋点(在现有逻辑基础上加,不改决策语义):
- 审批重发 `!ok` 分支 `record(...)` 后加 `metrics.recordDecision("deny");`
- `approvals.markConsumed(...)` 后加 `metrics.recordApproval("consumed");`
- `pdp.decide(...)` 改为计时:
```java
long pdp0 = System.nanoTime();
Decision d = pdp.decide(DecisionRequest.of(sub, obj, "read"));
metrics.recordPdpDecisionDuration(Duration.ofNanos(System.nanoTime() - pdp0));
```
- Effect.DENY 分支 `record(...)` 后加 `metrics.recordDecision("deny");`
- REQUIRE_APPROVAL 分支:`approvals.create(...)` 后加 `metrics.recordApproval("created");`,`record(...)` 后加 `metrics.recordDecision("require-approval");`

issueAndRun(90-100 行)埋点(allow / allow-approved 共用):
```java
private QueryResult issueAndRun(String sub, String obj, QueryIntent intent, String decision) {
    DbDynamicEngine engine = resources.require(intent.resource());
    long i0 = System.nanoTime();
    IssuedCred cred = engine.issue(intent.role(), Duration.ofHours(1));
    metrics.recordCredentialIssueDuration(Duration.ofNanos(System.nanoTime() - i0));
    metrics.recordCredentialIssued();
    try {
        long q0 = System.nanoTime();
        List<Map<String, Object>> rows = executor.runReadonly(engine.jdbcUrl(), cred, intent.sql());
        metrics.recordQueryDuration(Duration.ofNanos(System.nanoTime() - q0));
        record(sub, intent.tool(), obj, decision, rows.size() + " rows", cred.leaseId());
        metrics.recordDecision(decision);
        return QueryResult.ok(rows);
    } finally {
        long r0 = System.nanoTime();
        engine.revoke(cred.leaseId());
        metrics.recordCredentialRevokeDuration(Duration.ofNanos(System.nanoTime() - r0));
        metrics.recordCredentialRevoked();
    }
}
```
> `decision` 入参为 "allow" 或 "allow-approved",`recordDecision(decision)` 正好覆盖这两个放行计数。`import java.time.Duration;` 已在(issueAndRun 用了 Duration.ofHours)。

- [ ] **Step 5: 改现有 broker 测试构造器** —— 全局搜 broker 模块 `new BrokerService(` 调用点(BrokerServiceIT、BrokerAuditWiringTest、McpQueryToolServerTest),末尾加 `BrokerMetrics.NOOP`(或 BrokerServiceIT 审批/allow 用例可传 capturing 以备 Step 2 复用)。最小改动让编译通过、语义不变。

- [ ] **Step 6: 跑 broker verify** — Run: `mvn --% -pl broker -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿(BrokerMetricsIT 各路径断言 + 现有 BrokerServiceIT/BrokerAuditWiringTest/McpQueryToolServerTest 回归)

- [ ] **Step 7: Commit**
```bash
git add broker/
git commit -m "feat(broker): BrokerMetrics SPI + instrument queryDb decision/duration counters"
```

---

### Task 2: app — Micrometer/Actuator + Prometheus 端点 + gauge + 门控

**Files:**
- Modify: `app/pom.xml`（加 actuator + micrometer-registry-prometheus）
- Create: `app/src/main/java/io/custos/app/metrics/MicrometerBrokerMetrics.java`
- Create: `app/src/main/java/io/custos/app/metrics/MetricsConfig.java`
- Modify: `app/src/main/java/io/custos/app/operator/OperatorService.java`（注入 BrokerMetrics + assemble 传入 BrokerService）
- Modify: `app/src/main/java/io/custos/app/approval/ApprovalController.java`（approve/deny 记 metrics）
- Modify: `app/src/main/java/io/custos/app/security/AdminTokenFilter.java`（门控 `/actuator/prometheus`）
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（urlPatterns 加 `/actuator/prometheus`）
- Modify: `app/src/main/resources/application.yml`（management 暴露 health,prometheus）
- Test: `app/src/test/java/io/custos/app/MetricsEndpointIT.java`

- [ ] **Step 1: app/pom.xml 加依赖** —— 在现有 spring-boot starters 旁加(显式版本,因 parent 非 starter-parent):
```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId><version>3.3.2</version></dependency>
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId><version>1.13.2</version></dependency>
```
确认 `mvn -pl app -am dependency:tree` 拉到 micrometer-core 1.13.2(与 Boot 3.3.2 对齐)。

- [ ] **Step 2: 写 MicrometerBrokerMetrics**
```java
package io.custos.app.metrics;

import io.custos.broker.BrokerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** BrokerMetrics 的 Micrometer 实现。counter 按有界 tag 分,timer 记耗时。线程安全(Micrometer 保证)。 */
@Component
public class MicrometerBrokerMetrics implements BrokerMetrics {
    private final MeterRegistry registry;
    public MicrometerBrokerMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override public void recordDecision(String decision) {
        registry.counter("custos.decisions", "decision", decision).increment();
    }
    @Override public void recordApproval(String action) {
        registry.counter("custos.approvals", "action", action).increment();
    }
    @Override public void recordCredentialIssued() { registry.counter("custos.credentials.issued").increment(); }
    @Override public void recordCredentialRevoked() { registry.counter("custos.credentials.revoked").increment(); }
    @Override public void recordQueryDuration(Duration d) { timer("custos.query.duration").record(d); }
    @Override public void recordPdpDecisionDuration(Duration d) { timer("custos.pdp.decision.duration").record(d); }
    @Override public void recordCredentialIssueDuration(Duration d) { timer("custos.credential.issue.duration").record(d); }
    @Override public void recordCredentialRevokeDuration(Duration d) { timer("custos.credential.revoke.duration").record(d); }

    private Timer timer(String name) {
        return Timer.builder(name).publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }
}
```

- [ ] **Step 3: 写 MetricsConfig（4 gauge,数据源同 MonitorController）**

先 Read `MonitorController.java` 确认怎么取 seal/leases/resources/approvals(经 `op.status()` 与 `op.unsealed().leases()/resourceManager()/approvals()`)。gauge 用函数式注册,sealed 态读 op.status();解封前 unsealed() 可能抛——gauge lambda 要容错(sealed 时 leases/resources/approvals 取 0)。
```java
package io.custos.app.metrics;

import io.custos.app.operator.OperatorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 运行态 gauge:seal/活跃租约/资源数/待审批队深。解封前安全降级为 0。 */
@Component
public class MetricsConfig {
    private final OperatorService op;
    private final MeterRegistry registry;
    public MetricsConfig(OperatorService op, MeterRegistry registry) { this.op = op; this.registry = registry; }

    @PostConstruct
    public void register() {
        Gauge.builder("custos.seal.sealed", op, o -> o.status().sealed() ? 1.0 : 0.0).register(registry);
        Gauge.builder("custos.leases.active", op, o -> safe(() -> o.unsealed().leases().listActive().size())).register(registry);
        Gauge.builder("custos.resources.count", op, o -> safe(() -> o.unsealed().resourceManager().list().size())).register(registry);
        Gauge.builder("custos.approvals.pending", op, o -> safe(() -> o.unsealed().approvals().listPending().size())).register(registry);
    }
    private double safe(java.util.function.Supplier<Integer> s) {
        try { return s.get(); } catch (RuntimeException e) { return 0.0; }  // sealed/未装配时降级
    }
}
```
> `op.unsealed()` 在 sealed 时抛(M16 已知)——`safe(...)` 吞异常返 0。`op.status().sealed()` 任何时候可调。

- [ ] **Step 4: OperatorService 注入 BrokerMetrics + assemble 传入**

`OperatorService` 构造器(37 行 `(EngineBootstrap engine, TokenService tokens, Pdp pdp)`)加第 4 参 `BrokerMetrics metrics`(Spring 自动装 `MicrometerBrokerMetrics`),存字段。`assemble()`(90 行)的 `new BrokerService(tokens, pdp, resourceManager, new SecretlessQueryExecutor(), audit, approvals)` 末尾加 `, metrics`。import `io.custos.broker.BrokerMetrics`。
> 全局搜 app 内 `new OperatorService(` 调用点(测试若手动 new)——加 metrics 实参(测试可传 `BrokerMetrics.NOOP` 或 mock);若 OperatorService 全靠 Spring 注入则无需改测试。

- [ ] **Step 5: ApprovalController 记 approve/deny**

先 Read `ApprovalController.java`。注入 `BrokerMetrics metrics`(构造器加参,Spring 注 MicrometerBrokerMetrics),在 approve 成功后 `metrics.recordApproval("approved");`、deny 后 `metrics.recordApproval("denied");`。
> created/consumed 由 broker 记(Task 1),approved/denied 由此处记,凑齐 action 四态。

- [ ] **Step 6: application.yml management 暴露** —— 加:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      show-details: never
```

- [ ] **Step 7: AdminTokenFilter + HostConfig 门控 /actuator/prometheus**
  - AdminTokenFilter adminPath 加 `|| path.startsWith("/actuator/prometheus")`（**只门控 prometheus,不加 `/actuator` 前缀以免连带 health**）。
  - HostConfig `adminTokenFilter()` 的 `addUrlPatterns(...)` 加 `"/actuator/prometheus"`（精确,不用 `/actuator/*`）。

- [ ] **Step 8: 写 MetricsEndpointIT**

照 `ConsoleReadEndpointsIT` 模板(@SpringBootTest RANDOM_PORT + Testcontainers MySQL + @DynamicPropertySource + FixedTokenConfig 固定 admin token + 永不抛异常 RestTemplate + @BeforeAll seed 建 5 表 + init/unseal)。测试:
```java
// 解封后:
// 1) 无 token GET /actuator/prometheus → 401
// 2) GET /actuator/health 无 token → 200，body 含 "UP"
// 3) 带 admin token GET /actuator/prometheus → 200，body 含 "custos_seal_sealed"
// 4) 经一次真实 query_db(照 ConsoleReadEndpointsIT 注册 appdb+策略+ES256 token 那套)后,
//    带 admin token GET /actuator/prometheus → body 含 "custos_decisions_total"
```
最小必须覆盖 ①②③。④ 复用 ConsoleReadEndpointsIT 的 query_db 装配(若成本高可降级:只断言 ③ 的 gauge 存在)。

- [ ] **Step 9: 跑 app verify** — Run: `mvn --% -pl app -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿(MetricsEndpointIT + 现有回归)。先 `mvn -pl broker -am install -DskipTests` 确保上游 Task 1 的 BrokerMetrics 装进本地仓库。

- [ ] **Step 10: Commit**
```bash
git add app/
git commit -m "feat(app): Micrometer metrics + admin-gated /actuator/prometheus + runtime gauges"
```

---

### Task 3: compose 监控栈 + demo + 全量门禁 + 看板 + 合并

**Files:**
- Create: `examples/prometheus/prometheus.yml`
- Create: `examples/grafana/provisioning/datasources/prometheus.yml`
- Create: `examples/grafana/provisioning/dashboards/custos.yml`
- Create: `examples/grafana/dashboards/custos-dashboard.json`
- Modify: `examples/docker-compose.yml`（加 prometheus + grafana 服务）
- Modify: `examples/demo.md`（可观测段）
- Modify: `docs/spec/module/M17-observability.md`（status planned→done + subtasks/锚）
- 产物:`docs/cockpit.html` 重渲

- [ ] **Step 1: prometheus.yml**（scrape custos 带 Bearer）
```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: custos
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials: demo-token        # = compose 的 CUSTOS_ADMIN_TOKEN
    static_configs:
      - targets: ["custos:8080"]
```

- [ ] **Step 2: grafana provisioning**

`examples/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
`examples/grafana/provisioning/dashboards/custos.yml`:
```yaml
apiVersion: 1
providers:
  - name: Custos
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 3: custos-dashboard.json**（最小可用仪表盘,真实 PromQL）

创建 `examples/grafana/dashboards/custos-dashboard.json`,含面板:决策速率 `sum by (decision) (rate(custos_decisions_total[5m]))`、近窗拒绝率 `rate(custos_decisions_total{decision="deny"}[5m]) / ignoring(decision) sum(rate(custos_decisions_total[5m]))`、查询延迟 `histogram_quantile(0.95, rate(custos_query_duration_seconds_bucket[5m]))`、PDP 延迟同式、凭证签发/撤销速率 `rate(custos_credentials_issued_total[5m])`/`rate(custos_credentials_revoked_total[5m])`、seal stat `custos_seal_sealed`、活跃租约 `custos_leases_active`、审批队深 `custos_approvals_pending`。用标准 Grafana dashboard JSON schema(schemaVersion 39、`"datasource": {"type":"prometheus","uid":"${DS_PROMETHEUS}"}` 或匹配 provisioning datasource 名),`"title": "Custos 概览"`。**Grafana 启动加载后能渲染即可**——面板用 timeseries/stat 类型。
> 若手写完整 JSON 易错,可用 timeseries 面板最小集(每个 panel:gridPos/type/title/targets[expr])。务必是合法 JSON(Grafana provisioning 解析失败会整盘不出)。

- [ ] **Step 4: docker-compose 加 prometheus + grafana**

Read `examples/docker-compose.yml`(custos 服务名/端口 8080/CUSTOS_ADMIN_TOKEN=demo-token、console 在 3000)。在 console 后加:
```yaml
  prometheus:
    image: prom/prometheus:v2.54.1
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports: ["9090:9090"]
    depends_on:
      - custos
  grafana:
    image: grafana/grafana:11.2.0
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    ports: ["3001:3000"]
    depends_on:
      - prometheus
```
（缩进对齐现有 services；端口 3001 避开 console 的 3000。）

- [ ] **Step 5: demo.md 可观测段** —— 加「可观测性(M17)」:`docker compose up` 后 ① `curl -H "Authorization: Bearer demo-token" localhost:8080/actuator/prometheus` 见 `custos_*` 指标;无 token → 401;`/actuator/health` → 200 UP。② 浏览器开 Grafana `http://localhost:3001`(匿名 Viewer)→「Custos 概览」仪表盘,跑几次 query_db 后看决策/延迟/租约/seal 曲线。命令风格对齐现有 demo。

- [ ] **Step 6: 全量门禁** — Run: `mvn -B clean verify` Expected: BUILD SUCCESS(broker BrokerMetricsIT + app MetricsEndpointIT + 全回归)。compose/grafana 配置不进 reactor。

- [ ] **Step 7: M17 看板卡 done** —— Read `docs/spec/module/M17-observability.md`(planned)+ 一张 done 卡学风格。改 status→done、progress→100,加 subtasks S1-S3 + code 锚(broker/.../BrokerMetrics.java、app/.../metrics/MicrometerBrokerMetrics.java、examples/prometheus/prometheus.yml 等)+ doc 锚指 spec(`path#子串`,**先 Read spec 确认命中 `## ` 标题**,如 `#指标清单`/`#鉴权与暴露`/`#compose-监控栈`)。写完用 state.json 验证无 `heading not found`。

- [ ] **Step 8: 重渲 + lint** — Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml` 然后 `python3 -m docs_cockpit lint --config docs-cockpit.yaml` Expected: 0 error/0 warning;state.json M17 doc_anchors 无 `heading not found`。

- [ ] **Step 9: Commit + 合并 main + push**
```bash
git add examples/prometheus/ examples/grafana/ examples/docker-compose.yml examples/demo.md docs/spec/module/M17-observability.md docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "docs: M17 observability — compose Prometheus+Grafana + demo + dashboard card"
git checkout main
git merge --ff-only impl/m17-observability
git push origin main
```
> FF 前确认 main 未分叉(注:可能有 Codex 的并发提交,若 `--ff-only` 失败说明 main 前进了——**停下报告,别强推、别 rebase**,交回上层处理)。

---

## Self-Review

**1. Spec 覆盖:** §1 决策 D1(Micrometer+Actuator)→Task1/2;D2(no tracing)→不涉及;D3(prometheus admin-gated/health 开放)→Task2 Step6/7 + IT;D4(BrokerMetrics SPI 框架无关)→Task1;D5(compose Prometheus+Grafana)→Task3。§3 指标清单(4 counter + 4 timer + 4 gauge)→Task1 埋点 + Task2 MicrometerBrokerMetrics/MetricsConfig。§4 鉴权→Task2 Step6/7。§5 compose 栈→Task3。§6 测试→BrokerMetricsIT + MetricsEndpointIT。§7 交付物→Task1-3。§8 验收→Task3 门禁。§9 YAGNI(no tracing/no高基数 label/不推 Nacos/不写告警/console 不嵌 Grafana/只开 health+prometheus)→遵守。

**2. 占位符扫描:** BrokerMetrics/MicrometerBrokerMetrics/MetricsConfig/BrokerService 埋点给完整代码;prometheus.yml/grafana provisioning 给完整 YAML;dashboard JSON 给明确 PromQL 与面板清单(实现者按 Grafana schema 落 JSON——这是真实产物非占位)。"先 Read 确认"处(BrokerServiceIT 装配/命名、MonitorController 取数、ApprovalController 形状、spec heading)为核准动作。

**3. 类型一致:** `BrokerMetrics` 8 方法签名跨 NOOP/MicrometerBrokerMetrics/Capturing/BrokerService 调用一致;`BrokerService(...7 参...BrokerMetrics)` 与 OperatorService.assemble、broker 测试构造一致;`recordDecision` 的 decision 值(allow/deny/require-approval/allow-approved)与 issueAndRun 的 decision 入参、§3 tag 一致;`recordApproval` action(created/consumed 在 broker,approved/denied 在 ApprovalController)凑齐 §3 四态;gauge 名(custos.seal.sealed 等)与 §3、dashboard PromQL(custos_seal_sealed)的 Micrometer→Prometheus 命名转换一致;Prometheus counter 名带 `_total` 后缀(custos_decisions_total)与 dashboard PromQL 一致。
