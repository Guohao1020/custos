package io.custos.authz;

/** 默认保守钩子：未接审批前，高危一律不自动放行（→ REQUIRE_APPROVAL）。 */
public final class DenyApprovalHook implements ApprovalHook {
    @Override
    public boolean approve(DecisionRequest req, int risk) { return false; }
}
