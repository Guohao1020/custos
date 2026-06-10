package io.custos.engine.raft;

import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.option.NodeOptions;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 起一个 Raft KV 节点：状态机 + 日志/元数据/快照目录。进程内可起多节点（不同端口/目录）组成集群。 */
public final class RaftKvServer {

    private final RaftGroupService groupService;
    private final Node node;
    private final RaftKvStateMachine fsm;
    private final String serverId;

    private RaftKvServer(RaftGroupService groupService, Node node, RaftKvStateMachine fsm, String serverId) {
        this.groupService = groupService;
        this.node = node;
        this.fsm = fsm;
        this.serverId = serverId;
    }

    /** serverId 形如 "127.0.0.1:18091"；initialConf 形如 "127.0.0.1:18091,127.0.0.1:18092,..."。 */
    public static RaftKvServer start(String groupId, String serverId, String initialConf, Path dataDir) {
        RaftKvStateMachine fsm = new RaftKvStateMachine();
        NodeOptions opts = new NodeOptions();
        opts.setFsm(fsm);
        opts.setElectionTimeoutMs(1000);
        File base = dataDir.resolve(serverId.replace(':', '_')).toFile();
        new File(base, "log").mkdirs();
        new File(base, "raft_meta").mkdirs();
        new File(base, "snapshot").mkdirs();
        opts.setLogUri(new File(base, "log").getAbsolutePath());
        opts.setRaftMetaUri(new File(base, "raft_meta").getAbsolutePath());
        opts.setSnapshotUri(new File(base, "snapshot").getAbsolutePath());
        opts.setInitialConf(JRaftUtils.getConfiguration(initialConf));
        RaftGroupService gs = new RaftGroupService(groupId, JRaftUtils.getPeerId(serverId), opts);
        Node node = gs.start();
        return new RaftKvServer(gs, node, fsm, serverId);
    }

    /** 同组三节点进程内集群。 */
    public static List<RaftKvServer> startCluster(String groupId, List<String> peers, Path dataDir) {
        String conf = String.join(",", peers);
        List<RaftKvServer> out = new ArrayList<>();
        for (String p : peers) out.add(start(groupId, p, conf, dataDir));
        return out;
    }

    public RaftKvClient localClient() { return new RaftKvClient(node, fsm); }
    public Node node() { return node; }
    public RaftKvStateMachine fsm() { return fsm; }
    public String serverId() { return serverId; }
    public boolean isLeader() { return node.isLeader(); }

    public void shutdown() {
        groupService.shutdown();
    }
}
