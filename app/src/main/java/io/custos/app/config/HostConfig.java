package io.custos.app.config;

import io.custos.app.engine.EngineBootstrap;
import io.custos.app.operator.OperatorService;
import io.custos.app.security.AdminTokenFilter;
import io.custos.authz.CasbinPdp;
import io.custos.authz.ControlPlane;
import io.custos.authz.InMemoryControlPlane;
import io.custos.authz.NacosControlPlane;
import io.custos.authz.PolicyWatcher;
import io.custos.identity.InMemoryBlacklist;
import io.custos.identity.JwtTokenService;
import io.custos.identity.TokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

@Configuration
@EnableConfigurationProperties(CustosProperties.class)
public class HostConfig {

    @Bean
    public EngineBootstrap engineBootstrap(CustosProperties props) { return new EngineBootstrap(props); }

    @Bean
    public TokenService tokenService(CustosProperties props) throws Exception {
        var g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
        return new JwtTokenService(g.generateKeyPair(), props.getIdentity().getIssuer(), new InMemoryBlacklist());
    }

    @Bean
    public ControlPlane controlPlane(CustosProperties props) {
        // 默认租户 default 的控制面；PolicyService 用它读写默认策略。
        // InMemory 模式下此实例也被 pdp() 里 default 租户的 PolicyWatcher 复用（同一实例→状态共享，
        // 经 /policy PUT 的策略才能被该 watcher 看到）。
        return buildControlPlane(props, props.getNacos().getNamespace());
    }

    /**
     * 多租户 PDP 路由器：每个配置的租户一套独立 CasbinPdp + PolicyWatcher（各自 namespace 的控制面），
     * 按请求 tenant(=RBAC domain) 路由；未配置租户落 denyAll（默认拒，防隔离逃逸）。
     * tenants 为空时退化为单租户 default（namespace=nacos.namespace），向后兼容。
     *
     * <p>default 租户复用 {@code controlPlane} bean（InMemory 模式下与 PolicyService 共享同一实例），
     * 其它租户各建独立控制面。
     */
    @Bean
    public io.custos.authz.Pdp pdp(CustosProperties props, ControlPlane defaultControlPlane) {
        CasbinPdp denyAll = new CasbinPdp();
        denyAll.reload("");   // 空策略 = 默认拒，未配置租户兜底
        io.custos.authz.TenantPdpRouter router = new io.custos.authz.TenantPdpRouter(denyAll);

        java.util.List<CustosProperties.Tenant> tenants = props.getTenants();
        if (tenants == null || tenants.isEmpty()) {
            tenants = java.util.List.of(new CustosProperties.Tenant("default", props.getNacos().getNamespace()));
        }
        for (CustosProperties.Tenant t : tenants) {
            ControlPlane cp = "default".equals(t.getName())
                    ? defaultControlPlane                          // 复用 bean，与 PolicyService 共享
                    : buildControlPlane(props, t.getNamespace());  // 其它租户独立控制面（各自 namespace）
            CasbinPdp tpdp = new CasbinPdp();
            PolicyWatcher w = new PolicyWatcher(cp, props.getNacos().getPolicyDataId(), tpdp);
            w.start();                                             // 初载当前策略 + 订阅热推
            router.register(t.getName(), tpdp);
        }
        return router;
    }

    /** 按 namespace 构造控制面（InMemory/Nacos），供 controlPlane bean 与 pdp() 各租户共用，避免两份构造逻辑。 */
    private ControlPlane buildControlPlane(CustosProperties props, String namespace) {
        String addr = props.getNacos().getServerAddr();
        if (addr == null || addr.isBlank()) return new InMemoryControlPlane();
        return new NacosControlPlane(addr, namespace, props.getNacos().getGroup(),
                props.getNacos().getUsername(), props.getNacos().getPassword());
    }

    @Bean
    public io.custos.authz.ServiceRegistry serviceRegistry(CustosProperties props) {
        String addr = props.getNacos().getServerAddr();
        if (addr == null || addr.isBlank()) return new io.custos.authz.NoOpServiceRegistry();
        return new io.custos.authz.NacosServiceRegistry(addr, props.getNacos().getNamespace(),
                props.getNacos().getGroup(), props.getNacos().getUsername(), props.getNacos().getPassword());
    }

    @Bean
    public OperatorService operatorService(EngineBootstrap engine, TokenService tokens, io.custos.authz.Pdp pdp,
                                           io.custos.broker.BrokerMetrics metrics) {
        return new OperatorService(engine, tokens, pdp, metrics);
    }

    @Bean
    public io.custos.app.policy.PolicyService policyService(ControlPlane cp, CustosProperties props) {
        return new io.custos.app.policy.PolicyService(cp, props);
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
        FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(System.getenv("CUSTOS_ADMIN_TOKEN")));
        reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*", "/resources/*", "/approvals/*",
                "/leases/*", "/monitor/*", "/cluster/*", "/actuator/prometheus");   // 精确匹配 prometheus，放行 /actuator/health
        return reg;
    }
}
