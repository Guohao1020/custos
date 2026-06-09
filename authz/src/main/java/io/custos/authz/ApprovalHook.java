package io.custos.authz;

/** 高危 JIT 审批钩子 SPI：返回 true=已批准放行。 */
public interface ApprovalHook {
    boolean approve(DecisionRequest req, int risk);
}
