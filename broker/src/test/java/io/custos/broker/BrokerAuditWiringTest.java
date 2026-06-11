package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.audit.AuditRecord;
import io.custos.engine.audit.VerifyResult;
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
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元回归：经纪层每次拒绝决策都必须落一条哈希链审计行，且未注入 AuditLog 时不得 NPE。
 *
 * <p>守护一个真实出现过的接线缺口：app 装配 BrokerService 时漏传 AuditLog，
 * 导致所有 secret 访问决策都没写审计，与"防篡改审计"核心卖点相悖。
 * （allow 路径需真实 DB，由 {@code BrokerServiceIT} 覆盖。）
 *
 * <p>deny 路径不触达 {@code resources.require}，故传一个不挂任何资源的最小 ResourceManager 即可：
 * 内存 Storage 假实现 + 抛错占位 LeaseManager（都不会被 deny 路径调到）。
 */
class BrokerAuditWiringTest {

    /** 捕获式审计，仅记录被 append 的事件。query/count/decisionCounts 不被本用例触达。 */
    static final class CapturingAudit implements AuditLog {
        final List<AuditRecord> records = new ArrayList<>();
        public void append(AuditRecord r) { records.add(r); }
        public VerifyResult verify() { return VerifyResult.passed(); }
        public List<io.custos.engine.audit.AuditEntry> query(io.custos.engine.audit.AuditQuery q) { return List.of(); }
        public long count(io.custos.engine.audit.AuditQuery q) { return records.size(); }
        public Map<String, Long> decisionCounts(int recentWindow) { return Map.of(); }
    }

    /** 内存 Storage 假实现（deny 路径不触达资源解析，故内部不需真 DB）。 */
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new LinkedHashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String prefix) { return m.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList(); }
    }

    /** deny 路径不会触达租约，故任何调用都抛错以证明确未被调用。 */
    static final class UnreachableLeaseManager implements LeaseManager {
        public Lease register(String resourcePath, Duration ttl, Revoker revoker) { throw new AssertionError("deny 路径不应登记租约"); }
        public Lease renew(String leaseId, Duration increment) { throw new AssertionError("deny 路径不应续约"); }
        public void revoke(String leaseId) { throw new AssertionError("deny 路径不应撤销"); }
        public int revokePrefix(String prefix) { throw new AssertionError("deny 路径不应批量撤销"); }
        public List<Lease> listActive() { throw new AssertionError("deny 路径不应列举租约"); }
    }

    /** 最小 ResourceManager：不挂任何资源，deny 路径不会 require 到它。 */
    private ResourceManager minimalResources() {
        return new ResourceManager(new ResourceStore(new MemStorage()), new SecretsEngineRegistry(),
                new UnreachableLeaseManager(), null);
    }

    private TokenService tokens() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return new JwtTokenService(g.generateKeyPair(), "custos", new InMemoryBlacklist());
    }

    private String tokenFor(TokenService tokens, String agent) {
        return tokens.issue(new AgentId("corp.example", agent, "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
    }

    private CasbinPdp denyAllPdp() {
        CasbinPdp pdp = new CasbinPdp();
        pdp.reload("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default");
        return pdp;   // evil-agent 不在 g 规则里 → 默认拒绝
    }

    @Test
    void deniedDecisionWritesAuditRow() throws Exception {
        TokenService tokens = tokens();
        CapturingAudit audit = new CapturingAudit();
        BrokerService broker = new BrokerService(tokens, denyAllPdp(), minimalResources(),
                new SecretlessQueryExecutor(), audit, new InMemoryApprovalStore());

        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                tokenFor(tokens, "evil-agent"));

        assertFalse(r.allowed());
        assertEquals(1, audit.records.size(), "拒绝也必须落审计");
        AuditRecord rec = audit.records.get(0);
        assertEquals("deny", rec.decision());
        assertEquals("agent:evil-agent", rec.actor());
        assertEquals("tool:db/query_orders", rec.resource());
        assertEquals("read", rec.action());
    }

    @Test
    void nullAuditDoesNotThrow() throws Exception {
        TokenService tokens = tokens();
        BrokerService broker = new BrokerService(tokens, denyAllPdp(), minimalResources(),
                new SecretlessQueryExecutor(), null, new InMemoryApprovalStore());   // 无审计

        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                tokenFor(tokens, "evil-agent"));
        assertFalse(r.allowed());   // 不 NPE 即通过
    }
}
