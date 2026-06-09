package io.custos.engine.seal;

import io.custos.engine.barrier.Barrier;
import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSealManagerTest {

    /** 内存 SealStore，模拟跨重启的持久化。 */
    static final class InMemoryStore implements SealStore {
        final Map<String, byte[]> m = new HashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
    }

    @Test
    void initReturnsSharesAndStartsSealed() {
        InMemoryStore store = new InMemoryStore();
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        List<byte[]> shares = mgr.init(5, 3);
        assertEquals(5, shares.size());
        assertTrue(mgr.status().sealed());
    }

    @Test
    void unsealWithThresholdSharesUnlocksBarrier() {
        InMemoryStore store = new InMemoryStore();
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);

        // 模拟重启：新实例、同一 store、sealed
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        assertTrue(mgr.status().sealed());

        assertTrue(mgr.submitUnsealKey(shares.get(0)).sealed());   // 1/3
        assertTrue(mgr.submitUnsealKey(shares.get(1)).sealed());   // 2/3
        SealStatus after = mgr.submitUnsealKey(shares.get(2));     // 3/3 → unsealed
        assertFalse(after.sealed());

        // 解封后 barrier 可用
        Barrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        byte[] pt = "after-unseal".getBytes();
        assertArrayEquals(pt, barrier.open(barrier.seal(pt)));
    }

    @Test
    void operationsBeforeUnsealThrowSealed() {
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new InMemoryStore());
        // 未 init 也未 unseal
        assertThrows(SealedException.class, mgr::keyring);
    }

    @Test
    void sealClearsKeysAndRequiresReUnseal() {
        InMemoryStore store = new InMemoryStore();
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);
        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), store);
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(1));
        mgr.submitUnsealKey(shares.get(2));
        assertFalse(mgr.status().sealed());

        mgr.seal();
        assertTrue(mgr.status().sealed());
        assertThrows(SealedException.class, mgr::keyring);
    }
}
