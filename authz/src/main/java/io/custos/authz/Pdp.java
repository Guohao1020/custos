package io.custos.authz;

public interface Pdp {
    Decision decide(DecisionRequest req);
    /** 用新的策略 CSV 文本热重载（来自 ControlPlane）。 */
    void reload(String policyCsv);
}
