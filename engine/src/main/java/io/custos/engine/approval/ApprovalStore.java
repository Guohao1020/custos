package io.custos.engine.approval;

import java.util.List;
import java.util.Optional;

/** 审批单持久化。create 生成 id 并落 PENDING；approve/deny/markConsumed 按 id 流转状态。 */
public interface ApprovalStore {
    String create(String agent, String tool, String resource, String role, int risk, String reason);

    Optional<PendingApproval> get(String id);

    List<PendingApproval> listPending();

    void approve(String id, long expireAt);

    void deny(String id);

    void markConsumed(String id);
}
