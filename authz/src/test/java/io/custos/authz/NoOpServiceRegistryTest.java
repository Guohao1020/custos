package io.custos.authz;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoOpServiceRegistryTest {
    @Test
    void registerThenPeersHasSelf() {
        NoOpServiceRegistry r = new NoOpServiceRegistry();
        assertTrue(r.peers().isEmpty());
        r.register(new ServiceInstance("custos-host", "127.0.0.1", 8080, Map.of("version", "0.6")));
        assertEquals(1, r.peers().size());
        assertEquals("custos-host", r.peers().get(0).serviceName());
        r.deregister();
        assertTrue(r.peers().isEmpty());
    }
}
