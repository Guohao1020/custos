package io.custos.engine.audit;

import java.util.List;
import java.util.Map;

public interface AuditLog {
    void append(AuditRecord record);
    VerifyResult verify();

    /** 按条件分页查询（seq 降序，最新在前）。 */
    List<AuditEntry> query(AuditQuery q);

    /** 满足条件的总行数（忽略 page/size）。 */
    long count(AuditQuery q);

    /** 决策计数：recentWindow<=0 统计全量，>0 仅统计最近 recentWindow 行（按 seq 降序取）。key=decision。 */
    Map<String, Long> decisionCounts(int recentWindow);
}
