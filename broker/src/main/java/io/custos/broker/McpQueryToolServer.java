package io.custos.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 以 MCP server 暴露 query_db 工具（MCP Java SDK 2.0 API）：
 * McpServer.sync(transport).serverInfo(...).toolCall(tool, (exchange, request) -> CallToolResult).build()。
 * handler 调 BrokerService，只回结果文本（绝不回凭证）。
 *
 * <p>broker 经 Supplier 惰性解析：MCP server 可在 sealed 状态下先起（握手/tools-list 可用），
 * 工具调用时未解封则返回 isError 的明确提示，解封后无需重启即可服务。
 */
public final class McpQueryToolServer {

    private final Supplier<BrokerService> broker;

    public McpQueryToolServer(BrokerService broker) {
        this(() -> broker);
    }

    public McpQueryToolServer(Supplier<BrokerService> broker) {
        this.broker = broker;
    }

    /** 单次工具调用编排（独立出来便于纯单元测试，不触 stdio 传输）。 */
    McpSchema.CallToolResult handle(Map<String, Object> args) {
        BrokerService b;
        try {
            b = broker.get();
        } catch (IllegalStateException sealed) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.builder(
                            "SEALED: 引擎未解封，先经 REST /operator/unseal 提交阈值数分片再调用").build()))
                    .isError(true)
                    .build();
        }
        QueryResult r = b.queryDb(
                new QueryIntent((String) args.get("tool"), (String) args.get("resource"),
                        (String) args.getOrDefault("role", "read-only"), (String) args.get("sql")),
                (String) args.get("userToken"));
        String text = r.allowed() ? ("rows=" + r.rows()) : ("DENIED: " + r.denyReason());
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(text).build()))
                .isError(!r.allowed())
                .build();
    }

    public McpSyncServer start() {
        // 2.0：Tool.builder(name, Map 形式的输入 JSON Schema)
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tool", Map.of("type", "string"),
                        "resource", Map.of("type", "string"),
                        "role", Map.of("type", "string"),
                        "sql", Map.of("type", "string"),
                        "userToken", Map.of("type", "string")),
                "required", List.of("tool", "resource", "sql", "userToken"));

        McpSchema.Tool tool = McpSchema.Tool.builder("query_db", inputSchema)
                .description("对受治理只读库执行 SELECT，返回结果（凭证不出库）")
                .build();

        // 2.0：StdioServerTransportProvider 需注入 McpJsonMapper（这里用 jackson2 实现）
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

        return McpServer.sync(transport)
                .serverInfo("custos-broker", "0.1.0")
                .toolCall(tool, (exchange, request) -> handle(request.arguments()))
                .build();
    }
}
