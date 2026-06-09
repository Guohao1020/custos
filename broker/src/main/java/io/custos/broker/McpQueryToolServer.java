package io.custos.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 以 MCP server 暴露 query_db 工具（MCP Java SDK 2.0 API）：
 * McpServer.sync(transport).serverInfo(...).toolCall(tool, (exchange, request) -> CallToolResult).build()。
 * handler 调 BrokerService，只回结果文本（绝不回凭证）。
 */
public final class McpQueryToolServer {

    private final BrokerService broker;

    public McpQueryToolServer(BrokerService broker) {
        this.broker = broker;
    }

    public McpSyncServer start() {
        // 2.0：Tool.builder(name, Map 形式的输入 JSON Schema)
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tool", Map.of("type", "string"),
                        "schema", Map.of("type", "string"),
                        "sql", Map.of("type", "string"),
                        "userToken", Map.of("type", "string")),
                "required", List.of("tool", "schema", "sql", "userToken"));

        McpSchema.Tool tool = McpSchema.Tool.builder("query_db", inputSchema)
                .description("对受治理只读库执行 SELECT，返回结果（凭证不出库）")
                .build();

        // 2.0：StdioServerTransportProvider 需注入 McpJsonMapper（这里用 jackson2 实现）
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

        return McpServer.sync(transport)
                .serverInfo("custos-broker", "0.1.0")
                .toolCall(tool, (exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    QueryResult r = broker.queryDb(
                            new QueryIntent((String) args.get("tool"), (String) args.get("schema"), (String) args.get("sql")),
                            (String) args.get("userToken"));
                    String text = r.allowed() ? ("rows=" + r.rows()) : ("DENIED: " + r.denyReason());
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(text).build()))
                            .isError(!r.allowed())
                            .build();
                })
                .build();
    }
}
