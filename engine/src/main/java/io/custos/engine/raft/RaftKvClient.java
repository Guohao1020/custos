package io.custos.engine.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 进程内客户端：写经 leader node.apply(Task) 同步等待；读 leader-local 状态机（ReadIndex 为后续强化）。 */
public final class RaftKvClient {

    private final Node node;
    private final RaftKvStateMachine fsm;

    public RaftKvClient(Node node, RaftKvStateMachine fsm) {
        this.node = node;
        this.fsm = fsm;
    }

    public void put(String key, byte[] value) { apply(KvOp.put(key, value)); }

    public void delete(String key) { apply(KvOp.delete(key)); }

    public byte[] get(String key) { return fsm.get(key); }

    public List<String> list(String prefix) { return fsm.keysWithPrefix(prefix); }

    private void apply(KvOp op) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Status> result = new AtomicReference<>();
        Task task = new Task(op.encode(), status -> {
            result.set(status);
            latch.countDown();
        });
        node.apply(task);
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("raft apply timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("raft apply interrupted", e);
        }
        Status s = result.get();
        if (s == null || !s.isOk()) {
            throw new IllegalStateException("raft apply failed: " + s);
        }
    }
}
