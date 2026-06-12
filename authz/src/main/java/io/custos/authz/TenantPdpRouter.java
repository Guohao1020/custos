package io.custos.authz;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 RBAC domain(=tenant=Nacos namespace)把决策路由到各租户独立的 PDP。
 *
 * <p>安全闸:未注册/未知租户一律落 {@code denyAll}(默认拒),<b>绝不</b> fallback 到
 * 任何其他租户的策略——这是防"隔离逃逸"的硬边界,把请求 dom 当作不可信输入处理。
 *
 * <p>{@link #register} 与 {@link #decide} 可并发调用;底层 {@link ConcurrentHashMap}
 * 保证可见性与原子替换,各租户 PDP 自身的 reload 互不影响(只换该租户那一项)。
 */
public final class TenantPdpRouter implements Pdp {

    private final Map<String, Pdp> byTenant = new ConcurrentHashMap<>();
    private final Pdp denyAll;

    /** @param denyAll 兜底 PDP;约定为一个默认拒绝的 PDP(如空策略 CasbinPdp)。 */
    public TenantPdpRouter(Pdp denyAll) {
        this.denyAll = denyAll;
    }

    /** 注册/替换某租户的 PDP。线程安全。 */
    public void register(String tenant, Pdp pdp) {
        byTenant.put(tenant, pdp);
    }

    @Override
    public Decision decide(DecisionRequest req) {
        return byTenant.getOrDefault(req.dom(), denyAll).decide(req);
    }

    /**
     * 路由器本身无单一策略文本;reload 应作用于各租户 PDP 或经 {@link #register} 替换。
     * 这里转发给 {@code denyAll} 仅为契约完整性,不影响已注册租户。
     */
    @Override
    public void reload(String policyCsv) {
        denyAll.reload(policyCsv);
    }
}
