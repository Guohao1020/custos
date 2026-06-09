package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RevocationViaWatcherTest {

    private static final String ALLOW = """
            p, role:reader, tool:db/*, read, allow
            g, agent:claude-prod, role:reader
            """;
    private static final String REVOKED = """
            p, role:reader, tool:db/*, read, deny
            g, agent:claude-prod, role:reader
            """;

    @Test
    void changingPolicyRevokesWithinMillis() {
        InMemoryControlPlane cp = new InMemoryControlPlane();
        cp.publish("custos-policy", ALLOW);

        CasbinPdp pdp = new CasbinPdp();
        PolicyWatcher watcher = new PolicyWatcher(cp, "custos-policy", pdp);
        watcher.start();   // 初次加载 + 订阅

        DecisionRequest req = new DecisionRequest("agent:claude-prod", "tool:db/query_orders", "read");
        assertTrue(pdp.decide(req).allowed(), "吊销前应放行");

        long t0 = System.currentTimeMillis();
        cp.publish("custos-policy", REVOKED);     // 管理员改策略

        // 等待 watcher 重载（确定性：InMemoryControlPlane 同步回调）
        await().atMost(Duration.ofSeconds(2)).until(() -> !pdp.decide(req).allowed());
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(pdp.decide(req).allowed(), "吊销后应拒绝");
        assertTrue(elapsed < 2000, "应秒级生效，实测 " + elapsed + "ms");
    }
}
