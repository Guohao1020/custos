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
