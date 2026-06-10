package io.custos.engine.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 密文 KV 复制状态机：onApply 维护内存 Map（值为 Barrier 密文，本层不见明文）；快照为全量二进制文件。 */
public final class RaftKvStateMachine extends StateMachineAdapter {

    private static final String SNAPSHOT_FILE = "kv_snapshot";

    private final ConcurrentHashMap<String, byte[]> map = new ConcurrentHashMap<>();
    private final AtomicBoolean leader = new AtomicBoolean(false);

    public byte[] get(String key) { return map.get(key); }
    public java.util.List<String> keysWithPrefix(String prefix) {
        return map.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
    }
    public boolean isLeader() { return leader.get(); }

    @Override
    public void onApply(Iterator iter) {
        while (iter.hasNext()) {
            KvOp op = KvOp.decode(iter.getData().duplicate());
            if (op.type() == KvOp.PUT) {
                map.put(op.key(), op.value());
            } else if (op.type() == KvOp.DELETE) {
                map.remove(op.key());
            }
            if (iter.done() != null) {
                iter.done().run(Status.OK());
            }
            iter.next();
        }
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        try {
            File f = new File(writer.getPath(), SNAPSHOT_FILE);
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
                Map<String, byte[]> copy = Map.copyOf(map);
                out.writeInt(copy.size());
                for (Map.Entry<String, byte[]> e : copy.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }
            }
            if (writer.addFile(SNAPSHOT_FILE)) {
                done.run(Status.OK());
            } else {
                done.run(new Status(RaftError.EIO, "add snapshot file failed"));
            }
        } catch (Exception e) {
            done.run(new Status(RaftError.EIO, "save snapshot failed: %s", e.getMessage()));
        }
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        File f = new File(reader.getPath(), SNAPSHOT_FILE);
        if (!f.exists()) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            map.clear();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String k = in.readUTF();
                byte[] v = new byte[in.readInt()];
                in.readFully(v);
                map.put(k, v);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onLeaderStart(long term) {
        leader.set(true);
        super.onLeaderStart(term);
    }

    @Override
    public void onLeaderStop(Status status) {
        leader.set(false);
        super.onLeaderStop(status);
    }
}
