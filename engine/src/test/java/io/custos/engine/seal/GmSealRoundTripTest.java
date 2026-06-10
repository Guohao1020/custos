package io.custos.engine.seal;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.GmSuite;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 国密套件跑通完整 seal/unseal/barrier 链（ADR-4 切换可用性验证）。 */
class GmSealRoundTripTest {

    static final class MemStore implements SealStore {
        final Map<String, byte[]> m = new HashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
    }

    @Test
    void gmSuiteSealUnsealBarrierRoundTrip() {
        GmSuite suite = new GmSuite();
        MemStore store = new MemStore();
        List<byte[]> shares = new DefaultSealManager(suite, store).init(5, 3);

        DefaultSealManager mgr = new DefaultSealManager(suite, store);
        assertTrue(mgr.status().sealed());
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(1));
        assertFalse(mgr.submitUnsealKey(shares.get(2)).sealed());

        DefaultBarrier barrier = new DefaultBarrier(suite, mgr.keyring());
        assertArrayEquals("国密ok".getBytes(), barrier.open(barrier.seal("国密ok".getBytes())));
    }
}
