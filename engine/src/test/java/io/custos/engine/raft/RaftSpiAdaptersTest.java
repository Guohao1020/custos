package io.custos.engine.raft;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.lease.Lease;
import io.custos.engine.seal.DefaultSealManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RaftSpiAdaptersTest {

    static RaftKvServer server;
    static RaftKvClient client;

    @BeforeAll
    static void start(@TempDir Path dir) throws Exception {
        String peer = "127.0.0.1:18291";
        server = RaftKvServer.start("custos-kv-spi", peer, peer, dir);
        for (int i = 0; i < 100 && !server.isLeader(); i++) Thread.sleep(100);
        assertTrue(server.isLeader(), "leader within 10s");
        client = server.localClient();
    }

    @AfterAll
    static void stop() { server.shutdown(); }

    @Test
    void raftStorageRoundTripsAndLists() {
        RaftStorage storage = new RaftStorage(client);
        storage.put("k1", "v1".getBytes());
        storage.put("p/a", "1".getBytes());
        storage.put("p/b", "2".getBytes());
        assertArrayEquals("v1".getBytes(), storage.get("k1").orElseThrow());
        assertEquals(List.of("p/a", "p/b"), storage.list("p/"));
        storage.delete("k1");
        assertTrue(storage.get("k1").isEmpty());
    }

    @Test
    void sealRecoversAcrossManagerInstancesViaRaft() {
        RaftSealStore store = new RaftSealStore(client);
        List<byte[]> shares = new DefaultSealManager(new IntlSuite(), store).init(5, 3);

        DefaultSealManager mgr = new DefaultSealManager(new IntlSuite(), new RaftSealStore(client));
        assertTrue(mgr.status().sealed());
        mgr.submitUnsealKey(shares.get(0));
        mgr.submitUnsealKey(shares.get(2));
        assertFalse(mgr.submitUnsealKey(shares.get(4)).sealed());

        DefaultBarrier barrier = new DefaultBarrier(new IntlSuite(), mgr.keyring());
        assertArrayEquals("raft-ok".getBytes(), barrier.open(barrier.seal("raft-ok".getBytes())));
    }

    @Test
    void leaseRegisterRevokeRenewPrefix() {
        RaftLeaseManager leases = new RaftLeaseManager(client, () -> true);
        AtomicInteger revoked = new AtomicInteger();

        Lease l1 = leases.register("db/creds/orders-ro", Duration.ofHours(1), x -> revoked.incrementAndGet());
        leases.revoke(l1.leaseId());
        assertEquals(1, revoked.get());
        leases.revoke(l1.leaseId());                              // 幂等：已撤销不重复触发
        assertEquals(1, revoked.get());

        Lease l2 = leases.register("db/creds/x", Duration.ofMinutes(10), x -> { });
        assertTrue(leases.renew(l2.leaseId(), Duration.ofHours(2)).expireAt() > l2.expireAt());

        AtomicInteger n = new AtomicInteger();
        leases.register("aksk/app/k1", Duration.ofHours(1), x -> n.incrementAndGet());
        leases.register("aksk/app/k2", Duration.ofHours(1), x -> n.incrementAndGet());
        leases.register("other/k3", Duration.ofHours(1), x -> n.incrementAndGet());
        assertEquals(2, leases.revokePrefix("aksk/app/"));
        assertEquals(2, n.get());
    }

    @Test
    void sweeperIsLeaderOnlyAndAutoRevokesExpired() throws Exception {
        AtomicInteger followerRevoked = new AtomicInteger();
        RaftLeaseManager follower = new RaftLeaseManager(client, () -> false);   // 非 leader：不扫描
        follower.register("sweep/follower", Duration.ofMillis(200), x -> followerRevoked.incrementAndGet());
        Thread.sleep(2500);
        assertEquals(0, followerRevoked.get(), "非 leader 不应触发到期撤销");

        AtomicInteger leaderRevoked = new AtomicInteger();
        RaftLeaseManager leader = new RaftLeaseManager(client, () -> true);
        leader.register("sweep/leader", Duration.ofMillis(200), x -> leaderRevoked.incrementAndGet());
        for (int i = 0; i < 50 && leaderRevoked.get() == 0; i++) Thread.sleep(100);
        assertEquals(1, leaderRevoked.get(), "leader 应自动撤销到期租约");
    }
}
