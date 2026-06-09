package io.custos.engine.audit;

/** 一条待记录的审计事件。sensitiveRaw 为敏感原文，落盘前会被 HMAC 脱敏，绝不明文入库。 */
public record AuditRecord(long ts, String actor, String task, String resource,
                          String action, String decision, String resultDigest, String sensitiveRaw) {}
