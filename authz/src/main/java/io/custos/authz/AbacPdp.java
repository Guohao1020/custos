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
