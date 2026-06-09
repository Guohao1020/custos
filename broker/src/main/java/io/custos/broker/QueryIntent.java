package io.custos.broker;

/** 查询意图：tool=MCP 工具名(如 db/query_orders)，schema=目标库，sql=只读 SQL。 */
public record QueryIntent(String tool, String schema, String sql) {}
