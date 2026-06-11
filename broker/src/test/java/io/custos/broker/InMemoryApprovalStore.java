package io.custos.broker;

import io.custos.engine.approval.ApprovalStatus;
import io.custos.engine.approval.ApprovalStore;
import io.custos.engine.approval.PendingApproval;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存审批存储：deny / sealed 等不触达审批的路径只需它满足 BrokerService 构造。
 * 状态流转与 JimmerApprovalStore 等价（create→PENDING、approve/deny/markConsumed 改 status）。
 */
final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, PendingApproval> rows = new ConcurrentHashMap<>();

    @Override
    public String create(String agent, String tool, String resource, String role, int risk, String reason) {
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        rows.put(id, new PendingApproval(id, agent, tool, resource, role, risk,
                reason == null ? "" : reason, ApprovalStatus.PENDING, now, 0L, 0L));
        return id;
    }

    @Override
    public Optional<PendingApproval> get(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<PendingApproval> listPending() {
        return rows.values().stream().filter(a -> a.status() == ApprovalStatus.PENDING).toList();
    }

    @Override
    public void approve(String id, long expireAt) {
        update(id, a -> new PendingApproval(a.id(), a.agent(), a.tool(), a.resource(), a.role(), a.risk(),
                a.reason(), ApprovalStatus.APPROVED, a.createdAt(), System.currentTimeMillis(), expireAt));
    }

    @Override
    public void deny(String id) {
        update(id, a -> new PendingApproval(a.id(), a.agent(), a.tool(), a.resource(), a.role(), a.risk(),
                a.reason(), ApprovalStatus.DENIED, a.createdAt(), System.currentTimeMillis(), 0L));
    }

    @Override
    public void markConsumed(String id) {
        update(id, a -> new PendingApproval(a.id(), a.agent(), a.tool(), a.resource(), a.role(), a.risk(),
                a.reason(), ApprovalStatus.CONSUMED, a.createdAt(), a.decidedAt(), a.expireAt()));
    }

    private void update(String id, java.util.function.UnaryOperator<PendingApproval> fn) {
        PendingApproval cur = rows.get(id);
        if (cur == null) throw new IllegalArgumentException("no approval: " + id);
        rows.put(id, fn.apply(cur));
    }
}
