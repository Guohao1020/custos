package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.audit.AuditRecord;
import io.custos.engine.audit.VerifyResult;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngine;
import io.custos.identity.AgentId;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元回归：经纪层每次拒绝决策都必须落一条哈希链审计行，且未注入 AuditLog 时不得 NPE。
 *
 * <p>守护一个真实出现过的接线缺口：app 装配 BrokerService 时漏传 AuditLog，
 * 导致所有 secret 访问决策都没写审计，与"防篡改审计"核心卖点相悖。
 * （allow 路径需真实 DB，由 {@code BrokerServiceIT} 覆盖。）
 */
class BrokerAuditWiringTest {

    /** 捕获式审计，仅记录被 append 的事件。 */
    static final class CapturingAudit implements AuditLog {
        final List<AuditRecord> records = new ArrayList<>();
        public void append(AuditRecord r) { records.add(r); }
        public VerifyResult verify() { return VerifyResult.passed(); }
    }

    /** deny 路径不会触达，故签发/撤销均抛错以证明确未被调用。 */
    static final class UnreachableSecretsEngine implements SecretsEngine {
        public String type() { return "unreachable"; }
        public IssuedCred issue(String path, Duration ttl) { throw new AssertionError("deny 路径不应签发凭证"); }
        public void revoke(String leaseId) { throw new AssertionError("deny 路径不应撤销凭证"); }
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
        BrokerService broker = new BrokerService(tokens, denyAllPdp(), new UnreachableSecretsEngine(),
                new SecretlessQueryExecutor(), "jdbc:unused", audit);

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
        BrokerService broker = new BrokerService(tokens, denyAllPdp(), new UnreachableSecretsEngine(),
                new SecretlessQueryExecutor(), "jdbc:unused");   // 无审计构造器

        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                tokenFor(tokens, "evil-agent"));
        assertFalse(r.allowed());   // 不 NPE 即通过
    }
}
