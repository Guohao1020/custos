package io.custos.engine.audit;

/** 审计行只读投影：供 console 浏览。脱敏——不含哈希链内部字段（prev_hash/chain_hash/sensitive_hmac）。 */
public record AuditEntry(long seq, long ts, String actor, String task, String resource,
                         String action, String decision, String resultDigest) {}
