package io.custos.authz;

import java.util.List;

/** 可解释决策：effect 三态 + allowed(=ALLOW) + 命中策略 + risk(0..100) + 原因。 */
public record Decision(Effect effect, boolean allowed, List<String> matchedPolicies, int risk, String reason) {
    public static Decision allow(List<String> matched, int risk, String reason) { return new Decision(Effect.ALLOW, true, matched, risk, reason); }
    public static Decision deny(List<String> matched, int risk, String reason) { return new Decision(Effect.DENY, false, matched, risk, reason); }
    public static Decision requireApproval(List<String> matched, int risk, String reason) { return new Decision(Effect.REQUIRE_APPROVAL, false, matched, risk, reason); }
}
