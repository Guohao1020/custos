package io.custos.engine.approval;
import org.babyfish.jimmer.sql.*;

/** 审批单行。id 为应用生成的字符串主键（非自增）；status 流转 PENDING→APPROVED/DENIED→CONSUMED。 */
@Entity
@Table(name = "custos_approval")
public interface ApprovalRow {
    @Id String id();
    String agent();
    String tool();
    String resource();
    String role();
    int risk();
    String reason();
    String status();
    @Column(name = "created_at") long createdAt();
    @Column(name = "decided_at") long decidedAt();
    @Column(name = "expire_at") long expireAt();
}
