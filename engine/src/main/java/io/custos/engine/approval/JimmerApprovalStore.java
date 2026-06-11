package io.custos.engine.approval;

import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** custos_approval 的 Jimmer 持久化。id = 16 字节随机 hex（仅 [0-9a-f]）。 */
public final class JimmerApprovalStore implements ApprovalStore {
    private final JSqlClient sql;
    private final SecureRandom random = new SecureRandom();

    public JimmerApprovalStore(JSqlClient sql) { this.sql = sql; }

    @Override
    public String create(String agent, String tool, String resource, String role, int risk, String reason) {
        String id = hex(16);
        long now = System.currentTimeMillis();
        sql.getEntities().saveCommand(ApprovalRowDraft.$.produce(d -> {
            d.setId(id); d.setAgent(agent); d.setTool(tool); d.setResource(resource); d.setRole(role);
            d.setRisk(risk); d.setReason(reason == null ? "" : reason);
            d.setStatus(ApprovalStatus.PENDING.name());
            d.setCreatedAt(now); d.setDecidedAt(0L); d.setExpireAt(0L);
        })).setMode(SaveMode.INSERT_ONLY).execute();
        return id;
    }

    @Override
    public Optional<PendingApproval> get(String id) {
        ApprovalRowTable t = ApprovalRowTable.$;
        return sql.createQuery(t).where(t.id().eq(id)).select(t).execute().stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<PendingApproval> listPending() {
        ApprovalRowTable t = ApprovalRowTable.$;
        return sql.createQuery(t).where(t.status().eq(ApprovalStatus.PENDING.name()))
                .orderBy(t.createdAt().asc()).select(t).execute().stream().map(this::toDomain).toList();
    }

    @Override
    public void approve(String id, long expireAt) {
        setStatus(id, ApprovalStatus.APPROVED, System.currentTimeMillis(), expireAt);
    }

    @Override
    public void deny(String id) {
        setStatus(id, ApprovalStatus.DENIED, System.currentTimeMillis(), 0L);
    }

    @Override
    public void markConsumed(String id) {
        ApprovalRow cur = require(id);
        setStatus(id, ApprovalStatus.CONSUMED, cur.decidedAt(), cur.expireAt());
    }

    private void setStatus(String id, ApprovalStatus st, long decidedAt, long expireAt) {
        ApprovalRow cur = require(id);
        sql.getEntities().saveCommand(ApprovalRowDraft.$.produce(d -> {
            d.setId(id); d.setAgent(cur.agent()); d.setTool(cur.tool()); d.setResource(cur.resource()); d.setRole(cur.role());
            d.setRisk(cur.risk()); d.setReason(cur.reason());
            d.setStatus(st.name()); d.setCreatedAt(cur.createdAt()); d.setDecidedAt(decidedAt); d.setExpireAt(expireAt);
        })).setMode(SaveMode.UPSERT).execute();
    }

    private ApprovalRow require(String id) {
        ApprovalRowTable t = ApprovalRowTable.$;
        return sql.createQuery(t).where(t.id().eq(id)).select(t).execute().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no approval: " + id));
    }

    private PendingApproval toDomain(ApprovalRow r) {
        return new PendingApproval(r.id(), r.agent(), r.tool(), r.resource(), r.role(),
                r.risk(), r.reason(), ApprovalStatus.valueOf(r.status()), r.createdAt(), r.decidedAt(), r.expireAt());
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
