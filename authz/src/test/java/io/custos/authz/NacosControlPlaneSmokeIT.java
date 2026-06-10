package io.custos.authz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 仅当环境变量 NACOS_ADDR 存在时运行（计划 5 的 docker-compose 或本地 Nacos）。 */
@EnabledIfEnvironmentVariable(named = "NACOS_ADDR", matches = ".+")
class NacosControlPlaneSmokeIT {

    @Test
    void publishGetAndSubscribeRoundTrips() throws Exception {
        String addr = System.getenv("NACOS_ADDR");
        // Nacos 3.x 默认开 API 鉴权：NACOS_USERNAME/NACOS_PASSWORD 存在时带上（无鉴权 server 则不设）
        NacosControlPlane cp = new NacosControlPlane(addr, "public", "DEFAULT_GROUP",
                System.getenv("NACOS_USERNAME"), System.getenv("NACOS_PASSWORD"));

        cp.publish("custos-policy-it", "p, role:reader, tool:db/*, read, allow");
        Thread.sleep(300);
        assertNotNull(cp.get("custos-policy-it"));

        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        cp.subscribe("custos-policy-it", v -> { got.set(v); latch.countDown(); });

        cp.publish("custos-policy-it", "p, role:reader, tool:db/*, read, deny");
        assertTrue(latch.await(5, TimeUnit.SECONDS), "应收到秒级变更推送");
        assertTrue(got.get().contains("deny"));
    }
}
