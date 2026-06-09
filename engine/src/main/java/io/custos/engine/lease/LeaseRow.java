package io.custos.engine.lease;

import org.babyfish.jimmer.sql.*;

/** 租约行（custos_lease）。lease_id 为主键；revoked 标记是否已撤销。 */
@Entity
@Table(name = "custos_lease")
public interface LeaseRow {

    @Id
    @Column(name = "lease_id")
    String leaseId();

    @Column(name = "resource_path")
    String resourcePath();

    @Column(name = "issued_at")
    long issuedAt();

    @Column(name = "expire_at")
    long expireAt();

    boolean revoked();
}
