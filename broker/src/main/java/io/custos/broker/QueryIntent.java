package io.custos.broker;

/**
 * 查询意图：tool=MCP 工具名；resource=注册的资源名；role=签发角色（默认 read-only）；sql=只读 SQL；
 * approvalId=审批通过后重发时携带（null=首次请求）。
 */
public record QueryIntent(String tool, String resource, String role, String sql, String approvalId) {
    public QueryIntent(String tool, String resource, String role, String sql) {
        this(tool, resource, role, sql, null);
    }

    public QueryIntent(String tool, String resource, String sql) {
        this(tool, resource, "read-only", sql, null);
    }
}
