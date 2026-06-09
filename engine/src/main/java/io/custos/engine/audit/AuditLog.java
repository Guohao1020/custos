package io.custos.engine.audit;

public interface AuditLog {
    void append(AuditRecord record);
    VerifyResult verify();
}
