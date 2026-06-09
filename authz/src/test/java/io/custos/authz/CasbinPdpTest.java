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
