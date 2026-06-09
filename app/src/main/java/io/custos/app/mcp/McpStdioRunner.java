package io.custos.app.mcp;

import io.custos.app.operator.OperatorService;
import io.custos.broker.McpQueryToolServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** custos.transport.mcp-stdio=true 时，把 query_db 经 MCP stdio 暴露（需启动前已解封）。 */
@Component
public class McpStdioRunner implements CommandLineRunner {
    private final OperatorService op;
    @Value("${custos.transport.mcp-stdio:false}") boolean enabled;
    public McpStdioRunner(OperatorService op) { this.op = op; }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        new McpQueryToolServer(op.unsealed().broker()).start();
    }
}
