package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngine;
import io.custos.identity.AgentId;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守护 MCP 工具编排的两条非 DB 路径：sealed 时返回明确错误而非崩溃（修复 stdio 冷启动
 * op.unsealed() 必抛的接线缺口）；策略拒绝时 isError + DENIED 文本。
 */
class McpQueryToolServerTest {

    static final class UnreachableSecretsEngine implements SecretsEngine {
        public String type() { return "unreachable"; }
        public IssuedCred issue(String path, Duration ttl) { throw new AssertionError("不应触达签发"); }
        public void revoke(String leaseId) { throw new AssertionError("不应触达撤销"); }
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void sealedEngineReturnsErrorInsteadOfThrowing() {
        McpQueryToolServer mcp = new McpQueryToolServer(() -> { throw new IllegalStateException("sealed"); });
        McpSchema.CallToolResult r = mcp.handle(Map.of(
                "tool", "db/query_orders", "schema", "appdb", "sql", "SELECT 1", "userToken", "x"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("SEALED"));
    }

    @Test
    void deniedDecisionReturnsErrorWithReason() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        TokenService tokens = new JwtTokenService(g.generateKeyPair(), "custos", new InMemoryBlacklist());
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default");
        BrokerService broker = new BrokerService(tokens, pdp, new UnreachableSecretsEngine(),
                new SecretlessQueryExecutor(), "jdbc:unused");
        String evil = tokens.issue(new AgentId("corp.example", "evil-agent", "s1"),
                Set.of("x"), "broker", Duration.ofMinutes(5)).jwt();

        McpQueryToolServer mcp = new McpQueryToolServer(broker);
        McpSchema.CallToolResult r = mcp.handle(Map.of(
                "tool", "db/query_orders", "schema", "appdb", "sql", "SELECT 1", "userToken", evil));
        assertTrue(r.isError());
        assertTrue(text(r).startsWith("DENIED"));
    }
}
