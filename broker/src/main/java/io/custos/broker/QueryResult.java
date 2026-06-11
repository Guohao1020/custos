package io.custos.broker;

import java.util.List;
import java.util.Map;

/** 经纪返回：三态 + 行数据/拒因/审批 id。**永不包含任何凭证**。 */
public record QueryResult(QueryStatus status, List<Map<String, Object>> rows, String denyReason, String approvalId) {
    /** 兼容旧调用方：是否放行。 */
    public boolean allowed() { return status == QueryStatus.ALLOWED; }

    public static QueryResult ok(List<Map<String, Object>> rows) {
        return new QueryResult(QueryStatus.ALLOWED, rows, null, null);
    }

    public static QueryResult denied(String reason) {
        return new QueryResult(QueryStatus.DENIED, List.of(), reason, null);
    }

    public static QueryResult pending(String approvalId) {
        return new QueryResult(QueryStatus.PENDING, List.of(), "awaiting approval", approvalId);
    }
}
