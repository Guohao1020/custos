package io.custos.authz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantPdpRouter 按 RBAC domain(=租户)路由验证：
 * 两租户两策略验隔离 + reload 一个租户不影响另一个 + 未配置租户落 denyAll(防隔离逃逸)。
 *
 * <p>CSV 形状以 CasbinPdp 真实 model 为准:policy 行 6 列(p,sub,dom,obj,act,eft),
 * 主体经 g grouping policy 绑定角色(matcher: g(r.sub,p.sub,r.dom) && r.dom==p.dom)。
 */
class TenantPdpRouterTest {

    private CasbinPdp pdpWith(String csv) {
        CasbinPdp p = new CasbinPdp();
        p.reload(csv);
        return p;
    }

    @Test
    void routesByDomainAndIsolates() {
        // 租户 A:agent:x 经角色 reader 在 dom=A 可读 tool:t;租户 B:无任何策略(默认拒)。
        CasbinPdp a = pdpWith("""
                p, role:reader, A, tool:t, read, allow
                g, agent:x, role:reader, A
                """);
        CasbinPdp b = pdpWith("");                  // 空策略 = 默认拒
        CasbinPdp denyAll = pdpWith("");
        TenantPdpRouter router = new TenantPdpRouter(denyAll);
        router.register("A", a);
        router.register("B", b);

        assertTrue(router.decide(DecisionRequest.of("agent:x", "A", "tool:t", "read")).allowed(),
                "租户 A 有策略 → 放行");
        assertFalse(router.decide(DecisionRequest.of("agent:x", "B", "tool:t", "read")).allowed(),
                "租户 B 无策略 → 默认拒");
        assertFalse(router.decide(DecisionRequest.of("agent:x", "GHOST", "tool:t", "read")).allowed(),
                "未配置租户 → 落 denyAll 拒(防隔离逃逸,绝不 fallback 到别租户策略)");
    }

    @Test
    void reloadOneTenantDoesNotAffectOther() {
        CasbinPdp a = pdpWith("""
                p, role:reader, A, tool:t, read, allow
                g, agent:x, role:reader, A
                """);
        CasbinPdp b = pdpWith("""
                p, role:reader, B, tool:t, read, allow
                g, agent:y, role:reader, B
                """);
        TenantPdpRouter router = new TenantPdpRouter(pdpWith(""));
        router.register("A", a);
        router.register("B", b);

        a.reload("");                               // 撤销租户 A 的策略

        assertFalse(router.decide(DecisionRequest.of("agent:x", "A", "tool:t", "read")).allowed(),
                "租户 A 策略已撤销 → 现拒");
        assertTrue(router.decide(DecisionRequest.of("agent:y", "B", "tool:t", "read")).allowed(),
                "租户 B 不受 A 的 reload 影响 → 仍放行");
    }

    @Test
    void unknownTenantNeverFallsBackToAnotherTenantsPolicy() {
        // 显式隔离逃逸断言:即便存在一个会放行的租户 A,未知 dom 也绝不借用 A 的策略。
        CasbinPdp a = pdpWith("""
                p, role:reader, A, tool:t, read, allow
                g, agent:x, role:reader, A
                """);
        TenantPdpRouter router = new TenantPdpRouter(pdpWith(""));
        router.register("A", a);

        // 同一 sub/obj/act,只是把 dom 从 A 换成未注册的 ZZZ → 必须拒。
        assertTrue(router.decide(DecisionRequest.of("agent:x", "A", "tool:t", "read")).allowed());
        assertFalse(router.decide(DecisionRequest.of("agent:x", "ZZZ", "tool:t", "read")).allowed(),
                "未知租户 dom 不得命中租户 A 的放行策略");
    }
}
