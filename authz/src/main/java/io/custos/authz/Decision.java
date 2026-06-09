package io.custos.authz;

import java.util.List;

/** 可解释决策：是否放行 + 命中策略 + 人读原因。 */
public record Decision(boolean allowed, List<String> matchedPolicies, String reason) {}
