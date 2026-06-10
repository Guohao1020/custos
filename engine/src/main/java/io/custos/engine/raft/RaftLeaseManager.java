package io.custos.engine.raft;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** LeaseManager 的 Raft 落地：租约条目进状态机；到期扫描仅在 leader 执行（failover 后新 leader 接管）。 */
public final class RaftLeaseManager implements LeaseManager {

    private static final String PREFIX = "lease/";

    private final RaftKvClient client;
    private final BooleanSupplier isLeader;
    private final ConcurrentHashMap<String, Revoker> revokers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "custos-raft-lease-sweeper");
        t.setDaemon(true);
        return t;
    });

    public RaftLeaseManager(RaftKvClient client, BooleanSupplier isLeader) {
        this.client = client;
        this.isLeader = isLeader;
        scanner.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public Lease register(String resourcePath, Duration ttl, Revoker revoker) {
        String id = resourcePath + "/" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expire = now + ttl.toMillis();
        client.put(PREFIX + id, encode(resourcePath, now, expire, false));
        revokers.put(id, revoker);
        return new Lease(id, resourcePath, now, expire);
    }

    @Override
    public Lease renew(String leaseId, Duration increment) {
        byte[] raw = client.get(PREFIX + leaseId);
        if (raw == null) return null;
        Entry e = decode(raw);
        long expire = System.currentTimeMillis() + increment.toMillis();
        client.put(PREFIX + leaseId, encode(e.resourcePath, e.issuedAt, expire, e.revoked));
        return new Lease(leaseId, e.resourcePath, e.issuedAt, expire);
    }

    @Override
    public void revoke(String leaseId) {
        byte[] raw = client.get(PREFIX + leaseId);
        if (raw == null) return;
        Entry e = decode(raw);
        if (e.revoked) return;
        Revoker r = revokers.remove(leaseId);
        if (r != null) r.revoke(new Lease(leaseId, e.resourcePath, e.issuedAt, e.expireAt));
        client.put(PREFIX + leaseId, encode(e.resourcePath, e.issuedAt, e.expireAt, true));
    }

    @Override
    public int revokePrefix(String prefix) {
        List<String> keys = client.list(PREFIX + prefix);
        int n = 0;
        for (String key : keys) {
            Entry e = decode(client.get(key));
            if (!e.revoked) {
                revoke(key.substring(PREFIX.length()));
                n++;
            }
        }
        return n;
    }

    private void sweepExpired() {
        try {
            if (!isLeader.getAsBoolean()) return;            // leader-only：单扫描者保证"不重"
            long now = System.currentTimeMillis();
            for (String key : client.list(PREFIX)) {
                byte[] raw = client.get(key);
                if (raw == null) continue;
                Entry e = decode(raw);
                if (!e.revoked && e.expireAt < now) {
                    try { revoke(key.substring(PREFIX.length())); } catch (RuntimeException ignore) { }
                }
            }
        } catch (RuntimeException ignore) {
            // 下个周期重试
        }
    }

    // --- 条目编码：UTF resourcePath | long issuedAt | long expireAt | bool revoked ---
    private record Entry(String resourcePath, long issuedAt, long expireAt, boolean revoked) {}

    private static byte[] encode(String path, long issuedAt, long expireAt, boolean revoked) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(path);
            out.writeLong(issuedAt);
            out.writeLong(expireAt);
            out.writeBoolean(revoked);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Entry decode(byte[] raw) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            return new Entry(in.readUTF(), in.readLong(), in.readLong(), in.readBoolean());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
