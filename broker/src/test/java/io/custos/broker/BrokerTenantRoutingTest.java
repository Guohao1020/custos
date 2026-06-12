package io.custos.broker;

import io.custos.authz.CasbinPdp;
import io.custos.authz.TenantPdpRouter;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.resource.ResourceStore;
import io.custos.engine.secrets.SecretsEngineRegistry;
import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;
import io.custos.engine.lease.Revoker;
import io.custos.engine.storage.Storage;
import io.custos.identity.AgentId;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
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
 * 守护 QueryIntent.tenant 路由：BrokerService 把 intent.tenant() 作为 PDP 决策的 domain，
 * 经 TenantPdpRouter 路由到对应租户独立策略。证明 ① 默认 tenant="default" 命中现有 default 域策略
 * （向后兼容）；② 显式 tenant=A 命中 A 域策略；③ 未配置租户（ghost）落 denyAll 被拒（隔离闸）。
 *
 * <p>本类聚焦 PDP 决策路由，不接真 DB：被拒路径在 PDP 决策即返回 DENIED；放行路径通过 PDP 后才去
 * 解析资源，而最小 ResourceManager 未挂任何 secrets engine → 抛 "no secrets engine mounted"。
 * 故「放行」用「抛该异常」断言（证明已越过 PDP 闸、未被拒），「被拒」用 DENIED status 断言。
 */
class BrokerTenantRoutingTest {

    /** 内存 Storage 假实现（不触达资源解析）。 */
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new LinkedHashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String prefix) { return m.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList(); }
    }

    /** 被拒路径不触达租约，任何调用都抛错（放行断言只验证决策非 DENIED，不实际取数）。 */
    static final class UnreachableLeaseManager implements LeaseManager {
        public Lease register(String resourcePath, Duration ttl, Revoker revoker) { throw new AssertionError("不应登记租约"); }
        public Lease renew(String leaseId, Duration increment) { throw new AssertionError("不应续约"); }
        public void revoke(String leaseId) { throw new AssertionError("不应撤销"); }
        public int revokePrefix(String prefix) { throw new AssertionError("不应批量撤销"); }
        public List<Lease> listActive() { throw new AssertionError("不应列举租约"); }
    }

    private ResourceManager minimalResources() {
        return new ResourceManager(new ResourceStore(new MemStorage()), new SecretsEngineRegistry(),
                new UnreachableLeaseManager(), null);
    }

    private TokenService tokens;

    private String tokenFor(String agent) {
        return tokens.issue(new AgentId("corp.example", agent, "s1"),
                Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15)).jwt();
    }

    private BrokerService brokerWith(io.custos.authz.Pdp pdp) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            tokens = new JwtTokenService(g.generateKeyPair(), "custos", new InMemoryBlacklist());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new BrokerService(tokens, pdp, minimalResources(),
                new SecretlessQueryExecutor(), null, new InMemoryApprovalStore(), BrokerMetrics.NOOP);
    }

    /** dom=A 放行 tool:db/*；default 域空策略→默认拒；ghost 未注册→denyAll。 */
    private TenantPdpRouter routerWithTenantA() {
        CasbinPdp a = new CasbinPdp();
        a.reload("p, role:reader, A, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, A");
        CasbinPdp def = new CasbinPdp();
        def.reload("p, role:reader, default, tool:db/*, read, allow\ng, agent:claude-prod, role:reader, default");
        CasbinPdp denyAll = new CasbinPdp();
        denyAll.reload("");
        TenantPdpRouter router = new TenantPdpRouter(denyAll);
        router.register("A", a);
        router.register("default", def);
        return router;
    }

    @Test
    void defaultTenantRoutesToDefaultDomainPolicy() {
        BrokerService broker = brokerWith(routerWithTenantA());
        // 3 参构造：tenant 缺省 "default" → 命中 default 域策略 → 越过 PDP 闸 → 资源解析抛错（证明放行）。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                broker.queryDb(new QueryIntent("db/query_orders", "appdb", "SELECT 1"),
                        tokenFor("claude-prod")));
        assertTrue(ex.getMessage().contains("no secrets engine"),
                "默认 tenant 应命中 default 域策略放行（越过 PDP 后才在资源解析处抛错）");
    }

    @Test
    void explicitTenantRoutesToThatDomainPolicy() {
        BrokerService broker = brokerWith(routerWithTenantA());
        // 6 参构造，tenant="A" → 命中 A 域策略 → 越过 PDP 闸 → 资源解析抛错（证明放行）。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                broker.queryDb(
                        new QueryIntent("db/query_orders", "appdb", "read-only", "SELECT 1", null, "A"),
                        tokenFor("claude-prod")));
        assertTrue(ex.getMessage().contains("no secrets engine"),
                "tenant=A 应命中 A 域策略放行（越过 PDP 后才在资源解析处抛错）");
    }

    @Test
    void unconfiguredTenantFallsToDenyAll() {
        BrokerService broker = brokerWith(routerWithTenantA());
        // tenant="ghost" 未注册 → denyAll → DENIED（隔离闸，证明 tenant 影响路由 domain）。
        QueryResult r = broker.queryDb(
                new QueryIntent("db/query_orders", "appdb", "read-only", "SELECT 1", null, "ghost"),
                tokenFor("claude-prod"));
        assertEquals(QueryStatus.DENIED, r.status(), "未配置租户应落 denyAll 被拒");
    }
}
