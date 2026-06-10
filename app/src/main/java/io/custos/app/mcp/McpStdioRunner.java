package io.custos.app.mcp;

import io.custos.app.operator.OperatorService;
import io.custos.broker.McpQueryToolServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * custos.transport.mcp-stdio=true 时，把 query_db 经 MCP stdio 暴露。
 * broker 惰性解析：进程可 sealed 启动（MCP 握手可用），经 REST 解封后工具即可服务——
 * 否则 CommandLineRunner 在冷启动时 op.unsealed() 必抛、进程直接起不来。
 */
@Component
public class McpStdioRunner implements CommandLineRunner {
    private final OperatorService op;
    @Value("${custos.transport.mcp-stdio:false}") boolean enabled;
    public McpStdioRunner(OperatorService op) { this.op = op; }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        new McpQueryToolServer(() -> op.unsealed().broker()).start();
    }
}
