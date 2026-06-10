package io.custos.engine.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RaftKvSingleNodeTest {

    static void awaitLeader(RaftKvServer s) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (s.isLeader()) return;
            Thread.sleep(100);
        }
        fail("no leader within 10s");
    }

    @Test
    void singleNodePutGetDeleteAndSnapshotRestartRecovery(@TempDir Path dir) throws Exception {
        String peer = "127.0.0.1:18091";
        RaftKvServer server = RaftKvServer.start("custos-kv-single", peer, peer, dir);
        try {
            awaitLeader(server);
            RaftKvClient client = server.localClient();

            client.put("k1", "v1".getBytes());
            client.put("p/a", "1".getBytes());
            client.put("p/b", "2".getBytes());
            assertArrayEquals("v1".getBytes(), client.get("k1"));
            assertEquals(2, client.list("p/").size());

            client.delete("k1");
            assertNull(client.get("k1"));

            // 触发快照
            CountDownLatch snap = new CountDownLatch(1);
            server.node().snapshot(status -> snap.countDown());
            assertTrue(snap.await(10, TimeUnit.SECONDS), "snapshot should complete");
        } finally {
            server.shutdown();
        }

        // 重启同目录：从快照/日志恢复
        RaftKvServer restarted = RaftKvServer.start("custos-kv-single", peer, peer, dir);
        try {
            awaitLeader(restarted);
            assertArrayEquals("1".getBytes(), restarted.localClient().get("p/a"), "重启后状态应恢复");
            assertNull(restarted.localClient().get("k1"), "删除也应恢复");
        } finally {
            restarted.shutdown();
        }
    }
}
