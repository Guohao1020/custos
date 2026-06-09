# Custos ABAC / 风险分级 PDP（M08）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 authz 的 RBAC PDP 上叠加 domain 多租户 + ABAC（资源分级/风险/上下文）+ 三态决策（ALLOW/DENY/REQUIRE_APPROVAL）+ 高危 JIT 钩子，装饰链实现、可解释、纯单元可测。

**Architecture:** `CasbinPdp` 升级为带 `dom` 的 RBAC；`DecisionRequest`/`Decision` 富化（dom+ctx / effect+risk，带向后兼容工厂）；新增 `AbacPdp` 装饰 `CasbinPdp`，叠加越密级硬约束 + `RiskScorer` 评分 + 阈值分级 + `ApprovalHook`。

**Tech Stack:** Java 21 · org.casbin:jcasbin 1.55.0 · JUnit 5（沿用 authz 现有依赖，无新依赖）

> 前置：M04（CasbinPdp/ControlPlane/PolicyWatcher 已存在）。对应 spec `docs/superpowers/specs/2026-06-09-custos-abac-design.md`。
> **波及面**：Task 1 改 DecisionRequest/Decision/CasbinPdp，需同步更新调用点（BrokerService / CasbinPdpTest / RevocationViaWatcherTest / BrokerServiceIT / HostEndToEndIT / demo.md）并回归保绿。
> **依赖约定**：authz 无 custos 依赖，`mvn -pl authz test` 可独立跑；跨模块回归用根 `mvn -B verify`（reactor 从源码构建）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `authz/src/main/java/io/custos/authz/Effect.java` | 三态枚举 |
| `authz/src/main/java/io/custos/authz/RequestContext.java` | ABAC 属性包 |
| `authz/src/main/java/io/custos/authz/DecisionRequest.java` | 富化（+dom+ctx+of 工厂）|
| `authz/src/main/java/io/custos/authz/Decision.java` | 富化（+effect+risk+工厂）|
| `authz/src/main/java/io/custos/authz/CasbinPdp.java` | 升级带 domain |
| `authz/src/main/java/io/custos/authz/AbacPolicy.java` | 阈值/工作时段配置 |
| `authz/src/main/java/io/custos/authz/RiskScorer.java` + `DefaultRiskScorer.java` | 风险评分 SPI + 默认实现 |
| `authz/src/main/java/io/custos/authz/ApprovalHook.java` + `DenyApprovalHook.java` | 高危 JIT 钩子 SPI + 默认 |
| `authz/src/main/java/io/custos/authz/AbacPdp.java` | 装饰 CasbinPdp 的 ABAC PDP |
| `broker/.../BrokerService.java` | `DecisionRequest.of(...)` |
| 各测试 + `examples/demo.md` | dom 列策略 + `.of` |

---

## Task 1: domain 升级 + DecisionRequest/Decision 富化（含回归保绿）

**Files:**
- Create: `authz/src/main/java/io/custos/authz/Effect.java`
- Create: `authz/src/main/java/io/custos/authz/RequestContext.java`
- Modify: `authz/src/main/java/io/custos/authz/DecisionRequest.java`
- Modify: `authz/src/main/java/io/custos/authz/Decision.java`
- Modify: `authz/src/main/java/io/custos/authz/CasbinPdp.java`
- Modify: `authz/src/test/java/io/custos/authz/CasbinPdpTest.java`
- Modify: `authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java`
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`
- Modify: `broker/src/test/java/io/custos/broker/BrokerServiceIT.java`
- Modify: `app/src/test/java/io/custos/app/HostEndToEndIT.java`
- Modify: `examples/demo.md`

- [ ] **Step 1: 写 Effect + RequestContext**

`authz/src/main/java/io/custos/authz/Effect.java`:
```java
package io.custos.authz;

/** 决策结果三态。 */
public enum Effect { ALLOW, DENY, REQUIRE_APPROVAL }
```

`authz/src/main/java/io/custos/authz/RequestContext.java`:
```java
package io.custos.authz;

import java.util.Map;

/** ABAC 决策上下文属性包。键约定：clearance/resourceLevel(int)、hour(0-23)、ipTrusted(true/false)、intentSuspicious(true/false)。 */
public record RequestContext(Map<String, String> attributes) {
    public static RequestContext empty() { return new RequestContext(Map.of()); }
    public String attr(String k) { return attributes.get(k); }
    public int intAttr(String k, int dflt) {
        String v = attributes.get(k);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }
    public boolean boolAttr(String k) { return "true".equals(attributes.get(k)); }
}
```

- [ ] **Step 2: 富化 DecisionRequest（+dom+ctx+of 工厂）**

`authz/src/main/java/io/custos/authz/DecisionRequest.java`（整体替换）:
```java
package io.custos.authz;

/** 决策请求：sub=主体，dom=租户域(=Nacos namespace)，obj=工具，act=动作，ctx=ABAC 上下文。 */
public record DecisionRequest(String sub, String dom, String obj, String act, RequestContext ctx) {
    /** 向后兼容工厂：dom="default"、ctx 空。 */
    public static DecisionRequest of(String sub, String obj, String act) {
        return new DecisionRequest(sub, "default", obj, act, RequestContext.empty());
    }
}
```

- [ ] **Step 3: 富化 Decision（+effect+risk+工厂）**

`authz/src/main/java/io/custos/authz/Decision.java`（整体替换）:
```java
package io.custos.authz;

import java.util.List;

/** 可解释决策：effect 三态 + allowed(=ALLOW) + 命中策略 + risk(0..100) + 原因。 */
public record Decision(Effect effect, boolean allowed, List<String> matchedPolicies, int risk, String reason) {
    public static Decision allow(List<String> matched, int risk, String reason) { return new Decision(Effect.ALLOW, true, matched, risk, reason); }
    public static Decision deny(List<String> matched, int risk, String reason) { return new Decision(Effect.DENY, false, matched, risk, reason); }
    public static Decision requireApproval(List<String> matched, int risk, String reason) { return new Decision(Effect.REQUIRE_APPROVAL, false, matched, risk, reason); }
}
```

- [ ] **Step 4: CasbinPdp 升级带 domain**

`authz/src/main/java/io/custos/authz/CasbinPdp.java` 改两处：MODEL_TEXT 与 decide。
MODEL_TEXT 整体替换为：
```java
    private static final String MODEL_TEXT = """
            [request_definition]
            r = sub, dom, obj, act
            [policy_definition]
            p = sub, dom, obj, act, eft
            [role_definition]
            g = _, _, _
            [policy_effect]
            e = some(where (p.eft == allow)) && !some(where (p.eft == deny))
            [matchers]
            m = g(r.sub, p.sub, r.dom) && r.dom == p.dom && keyMatch2(r.obj, p.obj) && (r.act == p.act || p.act == "*")
            """;
```
decide 方法整体替换为：
```java
    @Override
    public Decision decide(DecisionRequest req) {
        Enforcer e = enforcerRef.get();
        EnforceResult res = e.enforceEx(req.sub(), req.dom(), req.obj(), req.act());
        boolean allow = res.isAllow();
        List<String> matched = res.getExplain();
        String reason = allow
                ? "命中允许策略: " + matched
                : "无匹配允许策略或命中拒绝策略（默认拒绝）: " + req;
        return allow ? Decision.allow(matched, 0, reason) : Decision.deny(matched, 0, reason);
    }
```
> `buildEnforcer`/`splitCsv` 不变——按 CSV 列数自适应（p 现 5 列、g 现 3 列）。

- [ ] **Step 5: 更新 CasbinPdpTest（dom 列策略 + .of + 跨租户隔离）**

`authz/src/test/java/io/custos/authz/CasbinPdpTest.java`（整体替换）:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CasbinPdpTest {

    private static final String POLICY = """
            p, role:reader, default, tool:db/*, read, allow
            p, role:reader, default, tool:db/*, write, deny
            g, agent:claude-prod, role:reader, default
            """;

    private CasbinPdp pdp() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload(POLICY);
        return pdp;
    }

    @Test
    void allowsReadForGrantedAgent() {
        Decision d = pdp().decide(DecisionRequest.of("agent:claude-prod", "tool:db/query_orders", "read"));
        assertTrue(d.allowed());
        assertFalse(d.matchedPolicies().isEmpty(), "应给出命中策略");
    }

    @Test
    void deniesWriteEvenForGrantedAgent() {
        Decision d = pdp().decide(DecisionRequest.of("agent:claude-prod", "tool:db/query_orders", "write"));
        assertFalse(d.allowed());
        assertNotNull(d.reason());
    }

    @Test
    void defaultDeniesUnknownAgent() {
        Decision d = pdp().decide(DecisionRequest.of("agent:unknown", "tool:db/query_orders", "read"));
        assertFalse(d.allowed(), "无匹配 → 默认拒绝");
    }

    @Test
    void crossTenantIsolation() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("""
                p, role:reader, tenantA, tool:db/*, read, allow
                g, agent:claude-prod, role:reader, tenantA
                """);
        assertTrue(pdp.decide(new DecisionRequest("agent:claude-prod", "tenantA", "tool:db/x", "read", RequestContext.empty())).allowed(), "同租户准");
        assertFalse(pdp.decide(new DecisionRequest("agent:claude-prod", "tenantB", "tool:db/x", "read", RequestContext.empty())).allowed(), "跨租户隔离");
    }
}
```

- [ ] **Step 6: 更新 RevocationViaWatcherTest（dom 列 + .of）**

`authz/src/test/java/io/custos/authz/RevocationViaWatcherTest.java` 改 ALLOW/REVOKED 策略与请求：
```java
    private static final String ALLOW = """
            p, role:reader, default, tool:db/*, read, allow
            g, agent:claude-prod, role:reader, default
            """;
    private static final String REVOKED = """
            p, role:reader, default, tool:db/*, read, deny
            g, agent:claude-prod, role:reader, default
            """;
```
并把 `new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "read")` 改为
`DecisionRequest.of("agent:claude-prod", "tool:db/query_orders", "read")`。

- [ ] **Step 7: 更新 BrokerService 调用点**

`broker/src/main/java/io/custos/broker/BrokerService.java` 把
`new DecisionRequest(sub, "tool:" + intent.tool(), "read")` 改为
`DecisionRequest.of(sub, "tool:" + intent.tool(), "read")`。

- [ ] **Step 8: 更新 broker/app 测试里的策略字符串（加 dom 列）**

`broker/src/test/java/io/custos/broker/BrokerServiceIT.java` 的 `pdp.reload(...)`：
```java
        pdp.reload("""
                p, role:reader, default, tool:db/*, read, allow
                g, agent:claude-prod, role:reader, default
                """);
```
`app/src/test/java/io/custos/app/HostEndToEndIT.java` 的 `policy.put(...)`：
```java
        policy.put("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default\n");
```

- [ ] **Step 9: 运行 authz 测试，确认通过**

Run: `mvn -pl authz test`
Expected: CasbinPdpTest（4，含跨租户）+ RevocationViaWatcherTest（1）PASS。

- [ ] **Step 10: 跨模块回归（broker/app 用新策略列）**

Run: `mvn -B verify`
Expected: 7 模块全绿（broker IT / HostEndToEndIT 用 dom 列策略 + .of 后通过）。

- [ ] **Step 11: 更新 demo.md 策略示例（加 dom 列）+ 提交**

`examples/demo.md` 把策略 `custos --token $TOKEN policy --content '...'` 与 curl 示例中的策略文本由
`p, role:reader, tool:db/*, read, allow` / `g, agent:claude-prod, role:reader`
改为 `p, role:reader, default, tool:db/*, read, allow` / `g, agent:claude-prod, role:reader, default`（吊销示例同理加 dom）。
```bash
git add authz/src/main/java/io/custos/authz/Effect.java authz/src/main/java/io/custos/authz/RequestContext.java authz/src/main/java/io/custos/authz/DecisionRequest.java authz/src/main/java/io/custos/authz/Decision.java authz/src/main/java/io/custos/authz/CasbinPdp.java authz/src/test/java/io/custos/authz broker/src/main/java/io/custos/broker/BrokerService.java broker/src/test/java/io/custos/broker/BrokerServiceIT.java app/src/test/java/io/custos/app/HostEndToEndIT.java examples/demo.md
git commit -m "feat(authz): RBAC domain (multi-tenant) + enrich DecisionRequest/Decision (effect+risk)"
```

---

## Task 2: RiskScorer + DefaultRiskScorer + AbacPolicy

**Files:**
- Create: `authz/src/main/java/io/custos/authz/AbacPolicy.java`
- Create: `authz/src/main/java/io/custos/authz/RiskScorer.java`
- Create: `authz/src/main/java/io/custos/authz/DefaultRiskScorer.java`
- Test: `authz/src/test/java/io/custos/authz/DefaultRiskScorerTest.java`

- [ ] **Step 1: 写 AbacPolicy + RiskScorer 接口**

`authz/src/main/java/io/custos/authz/AbacPolicy.java`:
```java
package io.custos.authz;

/** ABAC 阈值/工作时段（PBAC：可由 Nacos 配置热更）。 */
public record AbacPolicy(int approvalThreshold, int denyThreshold, int workStartHour, int workEndHour) {
    public static AbacPolicy defaults() { return new AbacPolicy(50, 80, 9, 18); }
}
```

`authz/src/main/java/io/custos/authz/RiskScorer.java`:
```java
package io.custos.authz;

/** 风险评分 SPI：返回 0..100。 */
public interface RiskScorer {
    int score(DecisionRequest req);
}
```

- [ ] **Step 2: 写失败测试**

`authz/src/test/java/io/custos/authz/DefaultRiskScorerTest.java`:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRiskScorerTest {

    private final RiskScorer scorer = new DefaultRiskScorer(AbacPolicy.defaults());

    private DecisionRequest req(String act, Map<String, String> ctx) {
        return new DecisionRequest("agent:x", "default", "tool:db/x", act, new RequestContext(ctx));
    }

    @Test
    void lowRiskReadEmptyCtx() {
        assertEquals(10, scorer.score(req("read", Map.of())), "read=10，空 ctx 不加分");
    }

    @Test
    void writeWithResourceLevel() {
        assertEquals(55, scorer.score(req("write", Map.of("resourceLevel", "1"))), "write40 + level1*15 = 55");
    }

    @Test
    void highRiskDeleteOffHoursHighLevel() {
        // delete70 + level2*30 + 非工作时段20 = 120 → clamp 100
        assertEquals(100, scorer.score(req("delete", Map.of("resourceLevel", "2", "hour", "23"))));
    }

    @Test
    void untrustedIpAndSuspiciousIntentAddRisk() {
        // read10 + ipTrusted=false 20 + intentSuspicious=true 25 = 55
        assertEquals(55, scorer.score(req("read", Map.of("ipTrusted", "false", "intentSuspicious", "true"))));
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl authz test -Dtest=DefaultRiskScorerTest`
Expected: 编译失败（DefaultRiskScorer 未定义）。

- [ ] **Step 4: 写 DefaultRiskScorer**

`authz/src/main/java/io/custos/authz/DefaultRiskScorer.java`:
```java
package io.custos.authz;

import java.util.Map;

/** 确定性加权评分：动作危险度 + 资源分级 + 上下文异常（非工作时段/不可信 IP/可疑意图）。 */
public final class DefaultRiskScorer implements RiskScorer {

    private static final Map<String, Integer> ACTION_BASE = Map.of("read", 10, "write", 40, "delete", 70, "admin", 90);

    private final AbacPolicy policy;

    public DefaultRiskScorer(AbacPolicy policy) { this.policy = policy; }

    @Override
    public int score(DecisionRequest req) {
        RequestContext c = req.ctx();
        int s = ACTION_BASE.getOrDefault(req.act(), 30);
        s += c.intAttr("resourceLevel", 0) * 15;
        int hour = c.intAttr("hour", 12);
        if (hour < policy.workStartHour() || hour >= policy.workEndHour()) s += 20;
        if ("false".equals(c.attr("ipTrusted"))) s += 20;          // 仅显式不信任 +20
        if (c.boolAttr("intentSuspicious")) s += 25;
        return Math.max(0, Math.min(100, s));
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl authz test -Dtest=DefaultRiskScorerTest`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 提交**
```bash
git add authz/src/main/java/io/custos/authz/AbacPolicy.java authz/src/main/java/io/custos/authz/RiskScorer.java authz/src/main/java/io/custos/authz/DefaultRiskScorer.java authz/src/test/java/io/custos/authz/DefaultRiskScorerTest.java
git commit -m "feat(authz): AbacPolicy + RiskScorer SPI + deterministic DefaultRiskScorer"
```

---

## Task 3: ApprovalHook + AbacPdp（装饰链三态分级）

**Files:**
- Create: `authz/src/main/java/io/custos/authz/ApprovalHook.java`
- Create: `authz/src/main/java/io/custos/authz/DenyApprovalHook.java`
- Create: `authz/src/main/java/io/custos/authz/AbacPdp.java`
- Test: `authz/src/test/java/io/custos/authz/AbacPdpTest.java`

- [ ] **Step 1: 写 ApprovalHook + DenyApprovalHook**

`authz/src/main/java/io/custos/authz/ApprovalHook.java`:
```java
package io.custos.authz;

/** 高危 JIT 审批钩子 SPI：返回 true=已批准放行。 */
public interface ApprovalHook {
    boolean approve(DecisionRequest req, int risk);
}
```

`authz/src/main/java/io/custos/authz/DenyApprovalHook.java`:
```java
package io.custos.authz;

/** 默认保守钩子：未接审批前，高危一律不自动放行（→ REQUIRE_APPROVAL）。 */
public final class DenyApprovalHook implements ApprovalHook {
    @Override
    public boolean approve(DecisionRequest req, int risk) { return false; }
}
```

- [ ] **Step 2: 写失败测试（六个分支）**

`authz/src/test/java/io/custos/authz/AbacPdpTest.java`:
```java
package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbacPdpTest {

    // 可控 RBAC delegate
    private Pdp rbac(boolean allow) {
        return new Pdp() {
            public Decision decide(DecisionRequest r) {
                return allow ? Decision.allow(List.of("p:allow"), 0, "rbac allow") : Decision.deny(List.of(), 0, "rbac deny");
            }
            public void reload(String csv) { }
        };
    }
    // 固定分数评分器
    private RiskScorer fixed(int v) { return r -> v; }

    private DecisionRequest req(Map<String, String> ctx) {
        return new DecisionRequest("agent:x", "default", "tool:db/x", "read", new RequestContext(ctx));
    }

    private final AbacPolicy pol = AbacPolicy.defaults();   // approval=50 deny=80

    @Test
    void rbacDenyShortCircuitsToDeny() {
        AbacPdp pdp = new AbacPdp(rbac(false), fixed(0), new DenyApprovalHook(), pol);
        assertEquals(Effect.DENY, pdp.decide(req(Map.of())).effect());
    }

    @Test
    void overClearanceDenies() {
        AbacPdp pdp = new AbacPdp(rbac(true), fixed(0), new DenyApprovalHook(), pol);
        Decision d = pdp.decide(req(Map.of("resourceLevel", "3", "clearance", "1")));
        assertEquals(Effect.DENY, d.effect());
    }

    @Test
    void lowRiskAllows() {
        AbacPdp pdp = new AbacPdp(rbac(true), fixed(10), new DenyApprovalHook(), pol);
        assertEquals(Effect.ALLOW, pdp.decide(req(Map.of())).effect());
    }

    @Test
    void midRiskRequiresApprovalByDefault() {
        AbacPdp pdp = new AbacPdp(rbac(true), fixed(60), new DenyApprovalHook(), pol);
        Decision d = pdp.decide(req(Map.of()));
        assertEquals(Effect.REQUIRE_APPROVAL, d.effect());
        assertEquals(60, d.risk());
    }

    @Test
    void midRiskAllowedWhenHookApproves() {
        AbacPdp pdp = new AbacPdp(rbac(true), fixed(60), (r, risk) -> true, pol);
        assertEquals(Effect.ALLOW, pdp.decide(req(Map.of())).effect());
    }

    @Test
    void highRiskDenies() {
        AbacPdp pdp = new AbacPdp(rbac(true), fixed(90), (r, risk) -> true, pol);
        assertEquals(Effect.DENY, pdp.decide(req(Map.of())).effect(), "≥deny 阈值即拒，钩子也不放行");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -q -pl authz test -Dtest=AbacPdpTest`
Expected: 编译失败（AbacPdp 未定义）。

- [ ] **Step 4: 写 AbacPdp**

`authz/src/main/java/io/custos/authz/AbacPdp.java`:
```java
package io.custos.authz;

import java.util.concurrent.atomic.AtomicReference;

/** ABAC PDP：装饰 RBAC PDP，叠加越密级硬约束 + 风险分级 + 高危 JIT 钩子。 */
public final class AbacPdp implements Pdp {

    private final Pdp delegate;
    private final RiskScorer scorer;
    private final ApprovalHook hook;
    private final AtomicReference<AbacPolicy> policyRef;

    public AbacPdp(Pdp delegate, RiskScorer scorer, ApprovalHook hook, AbacPolicy policy) {
        this.delegate = delegate;
        this.scorer = scorer;
        this.hook = hook;
        this.policyRef = new AtomicReference<>(policy);
    }

    @Override
    public Decision decide(DecisionRequest req) {
        Decision rbac = delegate.decide(req);
        if (!rbac.allowed()) return rbac;                         // RBAC 拒 → 直接 DENY

        AbacPolicy pol = policyRef.get();
        int level = req.ctx().intAttr("resourceLevel", 0);
        int clearance = req.ctx().intAttr("clearance", Integer.MAX_VALUE);
        if (level > clearance) {
            return Decision.deny(rbac.matchedPolicies(), 100, "越密级: resourceLevel=" + level + " > clearance=" + clearance);
        }

        int risk = scorer.score(req);
        if (risk >= pol.denyThreshold()) {
            return Decision.deny(rbac.matchedPolicies(), risk, "风险过高: risk=" + risk + " ≥ deny阈值" + pol.denyThreshold());
        }
        if (risk >= pol.approvalThreshold()) {
            if (hook.approve(req, risk)) {
                return Decision.allow(rbac.matchedPolicies(), risk, "中风险经审批放行: risk=" + risk);
            }
            return Decision.requireApproval(rbac.matchedPolicies(), risk, "中风险需审批: risk=" + risk);
        }
        return Decision.allow(rbac.matchedPolicies(), risk, "低风险放行: risk=" + risk);
    }

    @Override
    public void reload(String policyCsv) { delegate.reload(policyCsv); }

    /** PBAC 热更 ABAC 阈值/工作时段。 */
    public void reloadAbacPolicy(AbacPolicy p) { policyRef.set(p); }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -q -pl authz test -Dtest=AbacPdpTest`
Expected: PASS（6 个分支）。

- [ ] **Step 6: 运行全部 authz 测试 + 跨模块回归**

Run: `mvn -pl authz test` 然后 `mvn -B verify`
Expected: authz 全绿（CasbinPdp/Revocation/DefaultRiskScorer/AbacPdp）；7 模块 BUILD SUCCESS。

- [ ] **Step 7: 提交**
```bash
git add authz/src/main/java/io/custos/authz/ApprovalHook.java authz/src/main/java/io/custos/authz/DenyApprovalHook.java authz/src/main/java/io/custos/authz/AbacPdp.java authz/src/test/java/io/custos/authz/AbacPdpTest.java
git commit -m "feat(authz): AbacPdp decorator (over-clearance + risk bands + JIT hook), 3-state effect"
```

---

## Self-Review（对照 ABAC 设计 spec）

- **Spec 覆盖**：domain RBAC(§4)→Task1（CasbinPdp 模型 + 跨租户隔离测试）；DecisionRequest/Decision 富化(§3/§5)→Task1；RiskScorer/DefaultRiskScorer/AbacPolicy(§3)→Task2；ApprovalHook/DenyApprovalHook/AbacPdp 三态分级(§2/§3)→Task3；越密级硬约束(§2②)→Task3 `overClearanceDenies`；PBAC 热更(§6)→AbacPdp.reloadAbacPolicy。全覆盖。
- **类型一致性**：`Effect{ALLOW,DENY,REQUIRE_APPROVAL}`、`RequestContext(attributes)/attr/intAttr/boolAttr`、`DecisionRequest(sub,dom,obj,act,ctx)/of`、`Decision(effect,allowed,matchedPolicies,risk,reason)/allow|deny|requireApproval`、`AbacPolicy(approvalThreshold,denyThreshold,workStartHour,workEndHour)/defaults`、`RiskScorer.score`、`ApprovalHook.approve`、`AbacPdp(Pdp,RiskScorer,ApprovalHook,AbacPolicy)/reloadAbacPolicy` 跨任务一致。
- **占位扫描**：无 TODO/TBD；代码步均完整。
- **回归策略**：Task1 改 DecisionRequest/Decision/CasbinPdp 后，同步更新 BrokerService(.of)、CasbinPdpTest/RevocationViaWatcherTest(dom列+.of)、BrokerServiceIT/HostEndToEndIT(dom列策略)、demo.md，并 `mvn -B verify` 保 7 模块全绿。
- **风险评分确定性**：DefaultRiskScorer 纯算术 + clamp，可单测；空 ctx 不误判高危（hour 缺省 12、ipTrusted 仅显式 false +20）。
- **无新依赖**：authz 现有 jcasbin/JUnit。

> **下一子项目**：M09 AK·SK secrets engine（练 SecretsEngine SPI 第二实现）/ M12 SPIFFE（依赖 OBO 已就绪）。
