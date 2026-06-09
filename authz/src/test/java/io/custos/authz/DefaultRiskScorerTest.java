package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRiskScorerTest {

    private final RiskScorer scorer = new DefaultRiskScorer(AbacPolicy.defaults());

    private DecisionRequest req(String act, Map<String, String> ctx) {
        return new DecisionRequest("agent:x", "default", "tool:db/x", act, new RequestContext(ctx));
    }

    @Test
    void lowRiskReadEmptyCtx() {
        assertEquals(10, scorer.score(req("read", Map.of())), "read=10，空 ctx 不加分");
    }

    @Test
    void writeWithResourceLevel() {
        assertEquals(55, scorer.score(req("write", Map.of("resourceLevel", "1"))), "write40 + level1*15 = 55");
    }

    @Test
    void highRiskDeleteOffHoursHighLevel() {
        // delete70 + level2*30 + 非工作时段20 = 120 → clamp 100
        assertEquals(100, scorer.score(req("delete", Map.of("resourceLevel", "2", "hour", "23"))));
    }

    @Test
    void untrustedIpAndSuspiciousIntentAddRisk() {
        // read10 + ipTrusted=false 20 + intentSuspicious=true 25 = 55
        assertEquals(55, scorer.score(req("read", Map.of("ipTrusted", "false", "intentSuspicious", "true"))));
    }
}
