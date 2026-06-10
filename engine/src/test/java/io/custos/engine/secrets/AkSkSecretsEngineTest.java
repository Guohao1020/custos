package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AkSkSecretsEngineTest {

    /** 内存 LeaseManager 替身（无 DB）：register/renew/revoke 维护内存状态并触发 Revoker。 */
    static final class FakeLeaseManager implements LeaseManager {
        final Map<String, Lease> leases = new HashMap<>();
        final Map<String, Revoker> revokers = new HashMap<>();
        final java.util.Set<String> renewed = new java.util.HashSet<>();
        int seq = 0;

        @Override
        public Lease register(String path, Duration ttl, Revoker r) {
            String id = path + "/" + (seq++);
            long now = System.currentTimeMillis();
            Lease l = new Lease(id, path, now, now + ttl.toMillis());
            leases.put(id, l);
            revokers.put(id, r);
            return l;
        }

        @Override
        public Lease renew(String leaseId, Duration inc) {
            renewed.add(leaseId);
            Lease old = leases.get(leaseId);
            long now = System.currentTimeMillis();
            Lease l = new Lease(leaseId, old.resourcePath(), old.issuedAt(), now + inc.toMillis());
            leases.put(leaseId, l);
            return l;
        }

        @Override
        public void revoke(String leaseId) {
            Revoker r = revokers.remove(leaseId);
            if (r != null) r.revoke(leases.get(leaseId));
        }

        @Override
        public int revokePrefix(String prefix) { return 0; }
    }

    @Test
    void typeIsAkSk() {
        assertEquals("ak-sk", new AkSkSecretsEngine(new InMemoryAkSkProvider(), new FakeLeaseManager()).type());
    }

    @Test
    void issueReturnsActiveCred() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred c = eng.issue("app", Duration.ofHours(1));
        assertTrue(c.username().startsWith("AKIA"));
        assertFalse(c.password().isBlank());
        assertTrue(provider.isActive(c.username()), "AK 应活跃");
    }

    @Test
    void revokeDeactivatesAk() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred c = eng.issue("app", Duration.ofHours(1));
        eng.revoke(c.leaseId());
        assertFalse(provider.isActive(c.username()), "撤销后 AK 应失效");
    }

    @Test
    void hardRotateRevokesOldImmediately() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, new FakeLeaseManager());
        IssuedCred old = eng.issue("app", Duration.ofHours(1));
        IssuedCred fresh = eng.rotate(old.leaseId(), "app", Duration.ofHours(1), Duration.ZERO);
        assertFalse(provider.isActive(old.username()), "硬轮换：旧 AK 立即失效");
        assertTrue(provider.isActive(fresh.username()), "新 AK 活跃");
        assertNotEquals(old.leaseId(), fresh.leaseId(), "新旧 leaseId 不同");
    }

    @Test
    void graceRotateKeepsOldDuringWindow() {
        InMemoryAkSkProvider provider = new InMemoryAkSkProvider();
        FakeLeaseManager leases = new FakeLeaseManager();
        AkSkSecretsEngine eng = new AkSkSecretsEngine(provider, leases);
        IssuedCred old = eng.issue("app", Duration.ofHours(1));
        IssuedCred fresh = eng.rotate(old.leaseId(), "app", Duration.ofHours(1), Duration.ofMinutes(5));
        assertTrue(provider.isActive(old.username()), "grace 窗口内旧 AK 仍活跃");
        assertTrue(provider.isActive(fresh.username()), "新 AK 活跃");
        assertTrue(leases.renewed.contains(old.leaseId()), "旧租约被续到 grace");
    }
}
