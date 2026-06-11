package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.resource.ResourceStore;
import io.custos.engine.secrets.SecretsEngineRegistry;
import io.custos.engine.storage.Storage;
import io.custos.identity.AgentId;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守护 MCP 工具编排的两条非 DB 路径：sealed 时返回明确错误而非崩溃（修复 stdio 冷启动
 * op.unsealed() 必抛的接线缺口）；策略拒绝时 isError + DENIED 文本。
 * 两条路径都不触达资源解析，故传不挂任何资源的最小 ResourceManager。
 */
class McpQueryToolServerTest {

    /** 内存 Storage 假实现（不触达资源解析，无需真 DB）。 */
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new LinkedHashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String prefix) { return m.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList(); }
    }

    /** deny 路径不触达租约，任何调用都抛错。 */
    static final class UnreachableLeaseManager implements LeaseManager {
        public Lease register(String resourcePath, Duration ttl, Revoker revoker) { throw new AssertionError("不应登记租约"); }
        public Lease renew(String leaseId, Duration increment) { throw new AssertionError("不应续约"); }
        public void revoke(String leaseId) { throw new AssertionError("不应撤销"); }
        public int revokePrefix(String prefix) { throw new AssertionError("不应批量撤销"); }
    }

    private ResourceManager minimalResources() {
        return new ResourceManager(new ResourceStore(new MemStorage()), new SecretsEngineRegistry(),
                new UnreachableLeaseManager(), null);
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void sealedEngineReturnsErrorInsteadOfThrowing() {
        McpQueryToolServer mcp = new McpQueryToolServer(() -> { throw new IllegalStateException("sealed"); });
        McpSchema.CallToolResult r = mcp.handle(Map.of(
                "tool", "db/query_orders", "resource", "appdb", "sql", "SELECT 1", "userToken", "x"));
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
        BrokerService broker = new BrokerService(tokens, pdp, minimalResources(),
                new SecretlessQueryExecutor(), null);
        String evil = tokens.issue(new AgentId("corp.example", "evil-agent", "s1"),
                Set.of("x"), "broker", Duration.ofMinutes(5)).jwt();

        McpQueryToolServer mcp = new McpQueryToolServer(broker);
        McpSchema.CallToolResult r = mcp.handle(Map.of(
                "tool", "db/query_orders", "resource", "appdb", "sql", "SELECT 1", "userToken", evil));
        assertTrue(r.isError());
        assertTrue(text(r).startsWith("DENIED"));
    }
}
