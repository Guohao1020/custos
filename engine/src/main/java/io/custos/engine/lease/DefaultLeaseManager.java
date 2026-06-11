package io.custos.engine.lease;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 租约：register/renew/revoke/revokePrefix；后台扫描到期自动撤销。Revoker 留内存映射（MVP 单节点）。 */
public final class DefaultLeaseManager implements LeaseManager {

    private final JSqlClient sql;
    private final ConcurrentHashMap<String, Revoker> revokers = new ConcurrentHashMap<>();
    // 守护线程：扫描器不应阻止 JVM/测试 fork 退出
    private final ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "custos-lease-sweeper");
        t.setDaemon(true);
        return t;
    });

    public DefaultLeaseManager(JSqlClient sql) {
        this.sql = sql;
        scanner.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public Lease register(String resourcePath, Duration ttl, Revoker revoker) {
        String id = resourcePath + "/" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expire = now + ttl.toMillis();
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(id);
            d.setResourcePath(resourcePath);
            d.setIssuedAt(now);
            d.setExpireAt(expire);
            d.setRevoked(false);
        })).setMode(SaveMode.INSERT_ONLY).execute();   // 新行
        revokers.put(id, revoker);
        return new Lease(id, resourcePath, now, expire);
    }

    @Override
    public Lease renew(String leaseId, Duration increment) {
        long expire = System.currentTimeMillis() + increment.toMillis();
        // 残缺对象 + UPDATE_ONLY：只更新 expire_at（避免 UPSERT 的部分插入触发 NOT NULL）
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(leaseId);
            d.setExpireAt(expire);
        })).setMode(SaveMode.UPDATE_ONLY).execute();
        LeaseRow row = sql.getEntities().findById(LeaseRow.class, leaseId);
        return new Lease(leaseId, row.resourcePath(), row.issuedAt(), row.expireAt());
    }

    @Override
    public void revoke(String leaseId) {
        LeaseRow row = sql.getEntities().findById(LeaseRow.class, leaseId);
        if (row == null) return;
        Revoker r = revokers.remove(leaseId);
        if (r != null) r.revoke(new Lease(leaseId, row.resourcePath(), row.issuedAt(), row.expireAt()));
        sql.getEntities().saveCommand(LeaseRowDraft.$.produce(d -> {
            d.setLeaseId(leaseId);
            d.setRevoked(true);
        })).setMode(SaveMode.UPDATE_ONLY).execute();
    }

    @Override
    public int revokePrefix(String prefix) {
        LeaseRowTable t = LeaseRowTable.$;
        List<String> ids = sql.createQuery(t)
                .where(t.resourcePath().like(prefix + "%"))
                .where(t.revoked().eq(false))
                .select(t.leaseId())
                .execute();
        ids.forEach(this::revoke);
        return ids.size();
    }

    @Override
    public List<Lease> listActive() {
        long now = System.currentTimeMillis();                 // 与 register 写入、sweepExpired 比较同一毫秒基准
        LeaseRowTable t = LeaseRowTable.$;
        return sql.createQuery(t)
                .where(t.revoked().eq(false))
                .where(t.expireAt().gt(now))
                .orderBy(t.issuedAt().desc())
                .select(t)
                .execute()
                .stream()
                .map(r -> new Lease(r.leaseId(), r.resourcePath(), r.issuedAt(), r.expireAt()))
                .toList();
    }

    private void sweepExpired() {
        try {
            LeaseRowTable t = LeaseRowTable.$;
            long now = System.currentTimeMillis();
            List<String> expired = sql.createQuery(t)
                    .where(t.expireAt().lt(now))
                    .where(t.revoked().eq(false))
                    .select(t.leaseId())
                    .execute();
            for (String id : expired) {
                try { revoke(id); } catch (RuntimeException ignore) { /* 重试 + 告警（计划 5 接监控）*/ }
            }
        } catch (RuntimeException ignore) {
            // 下个周期重试
        }
    }
}
