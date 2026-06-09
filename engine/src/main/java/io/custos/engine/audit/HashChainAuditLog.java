package io.custos.engine.audit;

import io.custos.engine.crypto.CipherSuite;
import org.babyfish.jimmer.sql.JSqlClient;
import org.babyfish.jimmer.sql.ast.mutation.SaveMode;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** 哈希链审计：chain_hash_n = H(prev_hash || canonical)；敏感字段 HMAC 脱敏；只追加。Jimmer 实体读写。 */
public final class HashChainAuditLog implements AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final JSqlClient sql;
    private final CipherSuite suite;
    private final byte[] auditKey;

    public HashChainAuditLog(JSqlClient sql, CipherSuite suite, byte[] auditKey) {
        this.sql = sql;
        this.suite = suite;
        this.auditKey = auditKey.clone();
    }

    @Override
    public void append(AuditRecord r) {
        String prev = lastChainHash();
        String sensitiveHmac = hex(suite.hmac(auditKey, nz(r.sensitiveRaw()).getBytes(StandardCharsets.UTF_8)));
        String canonical = canonical(r.ts(), r.actor(), r.task(), r.resource(), r.action(), r.decision(), r.resultDigest(), sensitiveHmac);
        String chain = hex(suite.hash((prev + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
        // 审计行无 @Id(自增)/@Key → 必须 INSERT_ONLY（默认 UPSERT 会报 "neither id nor key"）
        sql.getEntities().saveCommand(
                AuditRowDraft.$.produce(d -> {
                    d.setTs(r.ts());
                    d.setActor(r.actor());
                    d.setTask(r.task());
                    d.setResource(r.resource());
                    d.setAction(r.action());
                    d.setDecision(r.decision());
                    d.setResultDigest(r.resultDigest());
                    d.setSensitiveHmac(sensitiveHmac);
                    d.setPrevHash(prev);
                    d.setChainHash(chain);
                    // seq 不设置 → DB 自增
                })
        ).setMode(SaveMode.INSERT_ONLY).execute();
    }

    @Override
    public VerifyResult verify() {
        AuditRowTable t = AuditRowTable.$;
        List<AuditRow> rows = sql.createQuery(t).orderBy(t.seq().asc()).select(t).execute();
        String expectedPrev = GENESIS;
        for (AuditRow row : rows) {
            if (!row.prevHash().equals(expectedPrev)) return VerifyResult.brokenAt(row.seq());
            String canonical = canonical(row.ts(), row.actor(), row.task(), row.resource(),
                    row.action(), row.decision(), row.resultDigest(), row.sensitiveHmac());
            String recompute = hex(suite.hash((row.prevHash() + "|" + canonical).getBytes(StandardCharsets.UTF_8)));
            if (!recompute.equals(row.chainHash())) return VerifyResult.brokenAt(row.seq());
            expectedPrev = row.chainHash();
        }
        return VerifyResult.passed();
    }

    private String lastChainHash() {
        AuditRowTable t = AuditRowTable.$;
        List<String> last = sql.createQuery(t).orderBy(t.seq().desc()).select(t.chainHash()).limit(1).execute();
        return last.isEmpty() ? GENESIS : last.get(0);
    }

    private static String canonical(long ts, String actor, String task, String resource,
                                    String action, String decision, String resultDigest, String sensitiveHmac) {
        return String.join("|", String.valueOf(ts), nz(actor), nz(task), nz(resource),
                nz(action), nz(decision), nz(resultDigest), nz(sensitiveHmac));
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
}
