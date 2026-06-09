package io.custos.broker;

import java.util.List;
import java.util.Map;

/** 经纪返回：行数据或拒绝原因。**永不包含任何凭证**。 */
public record QueryResult(boolean allowed, List<Map<String, Object>> rows, String denyReason) {
    public static QueryResult ok(List<Map<String, Object>> rows) { return new QueryResult(true, rows, null); }
    public static QueryResult denied(String reason) { return new QueryResult(false, List.of(), reason); }
}
