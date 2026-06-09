package io.custos.engine.audit;

import org.babyfish.jimmer.sql.*;
import org.jetbrains.annotations.Nullable;

/** 审计行（只追加）。seq 自增主键。chain_hash 由哈希链逐条计算，篡改任意字段都会断链。 */
@Entity
@Table(name = "custos_audit")
public interface AuditRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long seq();

    long ts();
    String actor();
    @Nullable String task();
    @Nullable String resource();
    @Nullable String action();
    @Nullable String decision();

    @Column(name = "result_digest")
    @Nullable String resultDigest();

    @Column(name = "sensitive_hmac")
    @Nullable String sensitiveHmac();

    @Column(name = "prev_hash")
    String prevHash();

    @Column(name = "chain_hash")
    String chainHash();
}
