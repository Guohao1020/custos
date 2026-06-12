package io.custos.authz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 仅当环境变量 NACOS_ADDR 存在时运行（计划 5 的 docker-compose 或本地 Nacos）。 */
@EnabledIfEnvironmentVariable(named = "NACOS_ADDR", matches = ".+")
class NacosServiceRegistryIT {

    @Test
    void registerSelectAndDeregisterRoundTrips() throws Exception {
        String addr = System.getenv("NACOS_ADDR");
        // Nacos 3.x 默认开 API 鉴权：NACOS_USERNAME/NACOS_PASSWORD 存在时带上（无鉴权 server 则不设）
        NacosServiceRegistry reg = new NacosServiceRegistry(addr, "public", "DEFAULT_GROUP",
                System.getenv("NACOS_USERNAME"), System.getenv("NACOS_PASSWORD"));

        ServiceInstance self = new ServiceInstance(
                "custos-host-it", "127.0.0.1", 18080, Map.of("version", "it"));
        reg.register(self);
        // Nacos 注册到健康可见有少量延迟，给注册中心同步窗口。
        Thread.sleep(800);

        List<ServiceInstance> peers = reg.peers();
        assertTrue(peers.stream().anyMatch(p ->
                        "127.0.0.1".equals(p.ip()) && p.port() == 18080),
                "peers() 应包含刚注册的 self 实例");

        reg.deregister();
        Thread.sleep(800);

        assertTrue(reg.peers().stream().noneMatch(p ->
                        "127.0.0.1".equals(p.ip()) && p.port() == 18080),
                "deregister 后 peers() 不应再含该实例");
    }
}
