package io.custos.engine.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftKvClusterTest {

    static RaftKvServer awaitLeaderOf(List<RaftKvServer> nodes) throws Exception {
        for (int i = 0; i < 150; i++) {
            for (RaftKvServer s : nodes) {
                if (s.isLeader()) return s;
            }
            Thread.sleep(100);
        }
        fail("no leader within 15s");
        return null;
    }

    static void awaitApplied(List<RaftKvServer> nodes, String key) throws Exception {
        for (int i = 0; i < 100; i++) {
            boolean all = nodes.stream().allMatch(s -> s.fsm().get(key) != null);
            if (all) return;
            Thread.sleep(100);
        }
        fail("key not replicated to all nodes within 10s");
    }

    @Test
    void replicatesToFollowersAndSurvivesLeaderFailover(@TempDir Path dir) throws Exception {
        List<String> peers = List.of("127.0.0.1:18191", "127.0.0.1:18192", "127.0.0.1:18193");
        List<RaftKvServer> nodes = RaftKvServer.startCluster("custos-kv-cluster", peers, dir);
        try {
            RaftKvServer leader = awaitLeaderOf(nodes);
            leader.localClient().put("k", "v".getBytes());
            awaitApplied(nodes, "k");                                     // 三个状态机都可见

            // kill leader → 新 leader 当选并继续读写
            leader.shutdown();
            List<RaftKvServer> remaining = new ArrayList<>(nodes);
            remaining.remove(leader);
            RaftKvServer newLeader = awaitLeaderOf(remaining);
            assertArrayEquals("v".getBytes(), newLeader.localClient().get("k"), "failover 后数据仍在");
            newLeader.localClient().put("k2", "v2".getBytes());
            awaitApplied(remaining, "k2");
            assertArrayEquals("v2".getBytes(), newLeader.localClient().get("k2"), "failover 后可继续写");
        } finally {
            for (RaftKvServer s : nodes) {
                try { s.shutdown(); } catch (Exception ignore) { }
            }
        }
    }
}
