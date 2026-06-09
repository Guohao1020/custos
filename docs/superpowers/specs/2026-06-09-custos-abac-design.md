# Custos ABAC / 风险分级 PDP 设计规格

> **类型**：生产架构路线图子项目 **M08 / P-ABAC**（v0.2）设计。在 authz 模块的 RBAC PDP 之上叠加 domain 多租户 + ABAC 属性/风险/上下文判定。
> **校订**：2026-06-09 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec `2026-06-09-custos-production-architecture-spec.md` §7；详设 `docs/design/04-authz-design.md`。

---

## 1. 目标与范围

在已交付的可解释 RBAC PDP（M04）之上：① **RBAC 加 domain**（=Nacos namespace）做多租户隔离；② **ABAC**——在决策中按 资源分级 / 风险分 / 上下文(时间·IP·意图) 收紧；③ 高危动作走 **JIT 审批钩子**；④ 三态决策（ALLOW / DENY / REQUIRE_APPROVAL）+ 风险分，且仍可解释。

- **纳入**：`Effect` 三态、`RequestContext` 属性包、`DecisionRequest`/`Decision` 富化、`RiskScorer` SPI + 默认实现、`AbacPolicy` 阈值配置、`ApprovalHook` SPI + 默认保守实现、`AbacPdp`（装饰 CasbinPdp）、`CasbinPdp` 升级为带 domain 的 RBAC。纯 Java、可单测。
- **非目标（留后续）**：完整 JIT 人工审批工作流（M08 只给钩子 + 默认实现）；ABAC 规则的可视化编辑；机器学习风险模型。准/拒最终由本 PDP 给出，PEP（broker）消费三态结果。
- **落地方式（已定）**：**装饰链 + Java 风险逻辑**（非 casbin-native 对象 matcher）；三态 `Effect`+risk；**dom 作策略列 + 请求携 dom**。

---

## 2. 架构与数据流（装饰链）

```
DecisionRequest(sub, dom, obj, act, ctx)
        │  Pdp.decide
        ▼
AbacPdp（装饰）
  ① Decision rbac = delegate.decide(req)          // CasbinPdp：RBAC + domain 粗筛
     若 !rbac.allowed() → 直接返回 DENY（带 RBAC 原因）
  ② 硬约束：ctx.resourceLevel > ctx.clearance → DENY（越密级）
  ③ int risk = riskScorer.score(req)              // 资源分级 × 动作危险 × 上下文异常
  ④ 分级：
       risk ≥ policy.denyThreshold        → DENY
       risk ≥ policy.approvalThreshold     → ApprovalHook.approve(req,risk) ? ALLOW : REQUIRE_APPROVAL
       else                                → ALLOW
        ▼
Decision(effect, allowed=(effect==ALLOW), matchedPolicies, risk, reason)
        │  PEP(broker) 按 effect 消费：ALLOW 放行 / DENY 拒 / REQUIRE_APPROVAL 转审批
        ▼
```
依赖方向遵循解耦铁律：`AbacPdp` 依赖 `Pdp`(delegate) + `RiskScorer` + `ApprovalHook` + `AbacPolicy` 接口/值对象；`CasbinPdp` 不依赖 ABAC 任何类。

---

## 3. 组件与契约

`authz/src/main/java/io/custos/authz/`：

```java
public enum Effect { ALLOW, DENY, REQUIRE_APPROVAL }

/** 决策上下文属性包（ABAC 用）。键约定：clearance/resourceLevel(int)、hour(0-23)、ipTrusted(true/false)、intentSuspicious(true/false) 等。 */
public record RequestContext(java.util.Map<String, String> attributes) {
    public static RequestContext empty() { return new RequestContext(java.util.Map.of()); }
    public String attr(String k) { return attributes.get(k); }
    public int intAttr(String k, int dflt) { /* parse 或 dflt */ }
    public boolean boolAttr(String k) { /* "true".equals */ }
}

/** 富化：加 dom(=namespace) + ctx。向后兼容工厂 of(sub,obj,act)。 */
public record DecisionRequest(String sub, String dom, String obj, String act, RequestContext ctx) {
    public static DecisionRequest of(String sub, String obj, String act) {
        return new DecisionRequest(sub, "default", obj, act, RequestContext.empty());
    }
}

/** 富化：加 effect 三态 + risk。allowed()=（effect==ALLOW）。 */
public record Decision(Effect effect, boolean allowed, java.util.List<String> matchedPolicies, int risk, String reason) {
    public static Decision allow(java.util.List<String> matched, int risk, String reason) { return new Decision(Effect.ALLOW, true, matched, risk, reason); }
    public static Decision deny(java.util.List<String> matched, int risk, String reason) { return new Decision(Effect.DENY, false, matched, risk, reason); }
    public static Decision requireApproval(java.util.List<String> matched, int risk, String reason) { return new Decision(Effect.REQUIRE_APPROVAL, false, matched, risk, reason); }
}

/** 风险评分 SPI：0..100。 */
public interface RiskScorer { int score(DecisionRequest req); }

/** ABAC 阈值/参数（PBAC：可由 Nacos 配置热更）。 */
public record AbacPolicy(int approvalThreshold, int denyThreshold, int workStartHour, int workEndHour) {
    public static AbacPolicy defaults() { return new AbacPolicy(50, 80, 9, 18); }
}

/** 高危 JIT 审批钩子 SPI。默认 DenyApprovalHook 保守返回 false（未接审批前高危一律 REQUIRE_APPROVAL）。 */
public interface ApprovalHook { boolean approve(DecisionRequest req, int risk); }
public final class DenyApprovalHook implements ApprovalHook { public boolean approve(DecisionRequest r, int risk) { return false; } }

/** ABAC PDP：装饰 RBAC PDP，叠加越密级硬约束 + 风险分级 + JIT 钩子。 */
public final class AbacPdp implements Pdp {
    public AbacPdp(Pdp delegate, RiskScorer scorer, ApprovalHook hook, AbacPolicy policy) { ... }
    public Decision decide(DecisionRequest req) { /* §2 四步 */ }
    public void reload(String policyCsv) { delegate.reload(policyCsv); }   // RBAC 策略热更透传
    public void reloadAbacPolicy(AbacPolicy p) { /* 原子替换阈值（PBAC 热更 ABAC 参数）*/ }
}
```

**默认 `DefaultRiskScorer.score(req)`**（确定性、可单测）：
```
base = { read:10, write:40, delete:70, admin:90 }.getOrDefault(req.act(), 30)
+ ctx.intAttr("resourceLevel",0) * 15
+ (ctx.intAttr("hour",12) < workStart || hour >= workEnd ? 20 : 0)      // 非工作时段
+ ("false".equals(ctx.attr("ipTrusted")) ? 20 : 0)                       // 仅显式不信任来源 +20
+ (ctx.boolAttr("intentSuspicious") ? 25 : 0)                           // 可疑意图
→ clamp 0..100
```
> 注：`ipTrusted` 缺省（无此属性）按"已知/可信"处理（+0），仅显式 `"false"` 才 +20，避免空 ctx 把一切判高危。`workStart/workEnd` 取自 `AbacPolicy`。`hour` 缺省 12（工作时段，+0）。

---

## 4. RBAC domain 模型（CasbinPdp 升级）

```ini
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
```
策略（dom=Nacos namespace）：
```
p, role:reader, tenantA, tool:db/*, read, allow
g, agent:claude-prod, role:reader, tenantA
```
`CasbinPdp.decide` 改用 `enforceEx(req.sub(), req.dom(), req.obj(), req.act())`；`buildEnforcer` 的 `addNamedPolicy`/`addNamedGroupingPolicy` 自动适配多列（按 CSV 列数）。

---

## 5. 向后兼容与改动波及

- `DecisionRequest`：规范构造为 5 元组；`of(sub,obj,act)` 工厂（dom="default"、ctx 空）保现有调用语义。**需更新所有 `new DecisionRequest(a,b,c)` 调用点**为 `DecisionRequest.of(a,b,c)`：`BrokerService`、`CasbinPdpTest`、`RevocationViaWatcherTest`、`BrokerServiceIT`、`HostEndToEndIT`。
- `Decision`：规范构造为 5 元组；用静态工厂 `allow/deny/requireApproval`。**需更新 `CasbinPdp` 构造 Decision 处**（用 `Decision.allow(...)/deny(...)`，risk=0）。`allowed()`/`matchedPolicies()`/`reason()` 访问器保留，broker/测试不破。
- **RBAC 策略字符串加 dom 列**：所有现有策略 CSV（测试内 + demo.md + broker 默认策略）从 `p, role, obj, act, eft` 升为 `p, role, dom, obj, act, eft`、`g, sub, role` 升为 `g, sub, role, dom`，请求 dom 用 "default"。
- broker `DecisionRequest.of(sub, "tool:"+intent.tool(), "read")`——dom 默认 "default"（broker 暂用默认租户；多租户接线在宿主层按 token/namespace 注入 dom，属后续）。

---

## 6. PBAC 集成

- RBAC 策略（含 dom）沿用现有 `ControlPlane`/`PolicyWatcher` 通道（M04），热更/版本/秒级吊销不变。
- ABAC 阈值/工作时段（`AbacPolicy`）作为第二份声明式配置，可经 ControlPlane 热更（`reloadAbacPolicy`）——宿主接线复用现有 Watcher 模式；core 保持纯逻辑。

---

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| RBAC 拒 | 直接 DENY（带 RBAC matched/原因），不再算 ABAC |
| ctx 缺属性 | 用安全缺省（resourceLevel=0、hour=12 视为工作时段、ipTrusted 缺省可信、intentSuspicious=false）→ 不误判高危 |
| 越密级（resourceLevel>clearance）| DENY |
| risk 异常（评分器抛错）| 由实现保证不抛；默认评分器纯算术不抛 |

---

## 8. 测试策略（TDD，纯单元）

- `CasbinPdp`(domain)：同租户准、跨租户隔离（tenantA 策略不影响 tenantB 请求）、默认拒。
- `DefaultRiskScorer`：read 低分放行、delete+高资源级+非工作时段 高分、空 ctx 不误判高危。
- `AbacPdp`：RBAC 拒→DENY；越密级→DENY；低风险→ALLOW；中风险→REQUIRE_APPROVAL（默认钩子）/ALLOW（approve 钩子）；高风险→DENY；reason 含 risk。
- 回归：现有 `CasbinPdpTest`/`RevocationViaWatcherTest`/broker IT 用 `DecisionRequest.of` + dom 列策略后仍绿。

---

## 9. 非目标 / YAGNI

- 不做完整审批工作流/审批 UI（仅 ApprovalHook + 默认保守实现）。
- 不做 casbin-native 对象 matcher（风险逻辑在 Java，更可控）。
- 不引入 ML 风险模型；评分器为确定性加权（可后续换实现）。
- 不在本增量做"每 namespace 独立 watcher"（dom 作策略列即可）。
