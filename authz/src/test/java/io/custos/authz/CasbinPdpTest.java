package io.custos.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CasbinPdpTest {

    // CSV 策略文本：reader 角色可只读 db 工具；claude-prod 属于 reader
    private static final String POLICY = """
            p, role:reader, tool:db/*, read, allow
            p, role:reader, tool:db/*, write, deny
            g, agent:claude-prod, role:reader
            """;

    private CasbinPdp pdp() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload(POLICY);
        return pdp;
    }

    @Test
    void allowsReadForGrantedAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "read"));
        assertTrue(d.allowed());
        assertFalse(d.matchedPolicies().isEmpty(), "应给出命中策略");
    }

    @Test
    void deniesWriteEvenForGrantedAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "write"));
        assertFalse(d.allowed());
        assertNotNull(d.reason());
    }

    @Test
    void defaultDeniesUnknownAgent() {
        Decision d = pdp().decide(new DecisionRequest("agent:unknown", "tool:db/query_orders", "read"));
        assertFalse(d.allowed(), "无匹配 → 默认拒绝");
    }
}
