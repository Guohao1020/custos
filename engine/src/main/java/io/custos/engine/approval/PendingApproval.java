package io.custos.engine.approval;
/** 一条审批单的域视图（非密钥，明文持久化）。expireAt=approve 后的有效窗到期(ms)；decidedAt=裁决时刻。 */
public record PendingApproval(String id, String agent, String tool, String resource, String role,
                              int risk, String reason, ApprovalStatus status,
                              long createdAt, long decidedAt, long expireAt) {}
