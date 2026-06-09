package io.custos.authz;

/** 风险评分 SPI：返回 0..100。 */
public interface RiskScorer {
    int score(DecisionRequest req);
}
