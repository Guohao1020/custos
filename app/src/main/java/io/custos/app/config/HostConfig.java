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
    public CasbinPdp casbinPdp() { return new CasbinPdp(); }

    @Bean
    public ControlPlane controlPlane(CustosProperties props) {
        String addr = props.getNacos().getServerAddr();
        if (addr == null || addr.isBlank()) return new InMemoryControlPlane();
        return new NacosControlPlane(addr, props.getNacos().getNamespace(), props.getNacos().getGroup(),
                props.getNacos().getUsername(), props.getNacos().getPassword());
    }

    @Bean
    public PolicyWatcher policyWatcher(ControlPlane cp, CustosProperties props, CasbinPdp pdp) {
        PolicyWatcher w = new PolicyWatcher(cp, props.getNacos().getPolicyDataId(), pdp);
        w.start();
        return w;
    }

    @Bean
    public OperatorService operatorService(EngineBootstrap engine, TokenService tokens, CasbinPdp pdp, CustosProperties props) {
        return new OperatorService(engine, tokens, pdp, props,
                props.getEngine().getStorageUrl(), props.getEngine().getStorageUsername(), props.getEngine().getStoragePassword());
    }

    @Bean
    public io.custos.app.policy.PolicyService policyService(ControlPlane cp, CustosProperties props) {
        return new io.custos.app.policy.PolicyService(cp, props);
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter() {
        FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>(new AdminTokenFilter(System.getenv("CUSTOS_ADMIN_TOKEN")));
        reg.addUrlPatterns("/operator/*", "/policy/*", "/audit/*", "/token/*");
        return reg;
    }
}
