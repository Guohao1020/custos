# M18 Nacos 深接（Nacos Deep Integration）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ① custos-host 经 nacos-client `NamingService` 注册为 Nacos 服务实例 + 多 host 发现;② 每租户独立 Nacos namespace 的 `TenantPdpRouter`(各租户独立 CasbinPdp+PolicyWatcher,tenant 作 RBAC domain,未配置租户 deny-all)。默认租户 `default` 严格向后兼容。

**Architecture:** authz 加 `ServiceRegistry` SPI(Nacos/NoOp 双实现,沿用 ControlPlane 的 server-addr 空→NoOp 模式)+ `TenantPdpRouter implements Pdp`(按 dom 路由,未配置租户→denyAll)+ `DecisionRequest.of(sub,dom,obj,act)` 重载;broker `QueryIntent` 加 tenant(默认 `default`),`BrokerService` 传 domain=tenant;app `CustosProperties` 加 tenants/cluster 配置,`HostConfig` 把单 Pdp 重构为 TenantPdpRouter(每租户 ControlPlane+CasbinPdp+PolicyWatcher)+ ServiceRegistry bean + ServiceLifecycle(启动注册/关停注销)+ `ClusterController /cluster/peers`(admin-gated)。

**Tech Stack:** Java 21 · Maven · nacos-client(已在 authz,NamingService 与 ConfigService 同包,零新增依赖) · jCasbin(CasbinPdp) · Spring Boot 3.3.2 · Testcontainers MySQL(`*IT`→failsafe,api.version=1.40)。

**前置:** 分支 `impl/m18-nacos-deep`(待建,spec 已 commit `60da0a7`)。spec:`docs/superpowers/specs/2026-06-12-nacos-deep-design.md`。已确认现状:`DecisionRequest(sub,dom,obj,act,ctx)` + `of(sub,obj,act)`→dom="default"+`RequestContext.empty()`;`Pdp.decide(DecisionRequest)→Decision`;HostConfig 现有 beans `casbinPdp()`(36)、`controlPlane(props)`(39,InMemory/Nacos)、`policyWatcher(cp,props,casbinPdp)`(47)、`operatorService(engine,tokens,CasbinPdp pdp,...)`(54)、`policyService(cp,props)`(60);`OperatorService` 构造器收 `Pdp`(M17 后 `(EngineBootstrap,TokenService,Pdp,BrokerMetrics)`);AdminTokenFilter adminPath + HostConfig urlPatterns 模式已知;broker `BrokerService.queryDb` 在 53 用 `DecisionRequest.of(sub,obj,"read")`,`QueryIntent` 现 5 组件(tool,resource,role,sql,approvalId)。

**红线:**
- broker/authz **不新增依赖**(NamingService 已在 nacos-client)。
- **未配置 tenant 一律 deny-all**(防隔离逃逸);默认租户名 `default`(匹配现有 dom);server-addr 空 → NoOp registry + InMemory 单租户,向后兼容现有 demo/测试全过。
- 凭证/审计/secretless 语义不变;tenant 仅影响 PDP 路由(approvalId 重发路径跳过 PDP,不受影响)。

**约定:** PowerShell `mvn` 带点 `-D` 用 `mvn --%`;`*IT` 归 failsafe;真 Nacos IT 用 `@EnabledIfEnvironmentVariable` 环境门控(默认门禁跳过);看板用 `python3`;commit subject 英文 conventional,body 中文,末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 1: authz — ServiceRegistry SPI（Nacos/NoOp 双实现）

**Files:**
- Create: `authz/src/main/java/io/custos/authz/ServiceInstance.java`
- Create: `authz/src/main/java/io/custos/authz/ServiceRegistry.java`
- Create: `authz/src/main/java/io/custos/authz/NoOpServiceRegistry.java`
- Create: `authz/src/main/java/io/custos/authz/NacosServiceRegistry.java`
- Test: `authz/src/test/java/io/custos/authz/NoOpServiceRegistryTest.java`
- Test: `authz/src/test/java/io/custos/authz/NacosServiceRegistryIT.java`（环境门控）

- [ ] **Step 1: ServiceInstance + ServiceRegistry**
```java
// ServiceInstance.java
package io.custos.authz;
import java.util.Map;
/** 一个 custos 服务实例的注册视图。metadata 仅放非敏感信息(version/mcpEndpoint/sealed)。 */
public record ServiceInstance(String serviceName, String ip, int port, Map<String, String> metadata) {}
```
```java
// ServiceRegistry.java
package io.custos.authz;
import java.util.List;
/** 服务注册发现 SPI。server-addr 空→NoOp(单节点);非空→Nacos NamingService。 */
public interface ServiceRegistry {
    void register(ServiceInstance self);
    void deregister();
    /** 当前健康的同名服务实例(含 self)。 */
    List<ServiceInstance> peers();
}
```

- [ ] **Step 2: NoOpServiceRegistry + 失败单测**
```java
// NoOpServiceRegistry.java
package io.custos.authz;
import java.util.List;
/** 单节点占位:register 仅记住 self,peers 返回仅含 self。 */
public final class NoOpServiceRegistry implements ServiceRegistry {
    private volatile ServiceInstance self;
    @Override public void register(ServiceInstance self) { this.self = self; }
    @Override public void deregister() { this.self = null; }
    @Override public List<ServiceInstance> peers() { return self == null ? List.of() : List.of(self); }
}
```
```java
// NoOpServiceRegistryTest.java
package io.custos.authz;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class NoOpServiceRegistryTest {
    @Test void registerThenPeersHasSelf() {
        NoOpServiceRegistry r = new NoOpServiceRegistry();
        assertTrue(r.peers().isEmpty());
        r.register(new ServiceInstance("custos-host", "127.0.0.1", 8080, Map.of("version", "0.6")));
        assertEquals(1, r.peers().size());
        assertEquals("custos-host", r.peers().get(0).serviceName());
        r.deregister();
        assertTrue(r.peers().isEmpty());
    }
}
```

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl authz test -Dtest=NoOpServiceRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 4: NacosServiceRegistry**

**先核准 nacos-client 的 NamingService API**(项目硬规矩:第三方 API 写实现前核准)。Read `authz/src/main/java/io/custos/authz/NacosControlPlane.java` 看 props 构造(serverAddr/namespace/username/password)。确认本仓库 nacos-client 版本(authz/pom.xml)下 `com.alibaba.nacos.api.naming.NamingService` 的方法:`registerInstance(String serviceName, String groupName, Instance instance)`、`selectInstances(String serviceName, String groupName, boolean healthy)`、`deregisterInstance(String serviceName, String groupName, Instance instance)`、`Instance`(setIp/setPort/setMetadata/setHealthy)。以实际版本 API 为准微调。
```java
// NacosServiceRegistry.java（目标形状,按实际 NamingService API 落地）
package io.custos.authz;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/** Nacos NamingService 实现:custos-host 注册为服务实例 + 健康发现。props 同 NacosControlPlane。 */
public final class NacosServiceRegistry implements ServiceRegistry {
    private final NamingService naming;
    private final String group;
    private ServiceInstance self;

    public NacosServiceRegistry(String serverAddr, String namespace, String group,
                                String username, String password) {
        this.group = group;
        try {
            Properties props = new Properties();
            props.put("serverAddr", serverAddr);
            if (namespace != null && !namespace.isBlank()) props.put("namespace", namespace);
            if (username != null) { props.put("username", username); props.put("password", password); }
            this.naming = NacosFactory.createNamingService(props);
        } catch (Exception e) { throw new IllegalStateException("nacos naming init failed", e); }
    }

    @Override public void register(ServiceInstance s) {
        this.self = s;
        try {
            Instance ins = new Instance();
            ins.setIp(s.ip()); ins.setPort(s.port()); ins.setHealthy(true);
            ins.setMetadata(s.metadata());
            naming.registerInstance(s.serviceName(), group, ins);
        } catch (Exception e) { throw new IllegalStateException("register failed", e); }
    }

    @Override public void deregister() {
        if (self == null) return;
        try {
            Instance ins = new Instance();
            ins.setIp(self.ip()); ins.setPort(self.port());
            naming.deregisterInstance(self.serviceName(), group, ins);
        } catch (Exception e) { /* 关停期容错,记日志即可 */ }
    }

    @Override public List<ServiceInstance> peers() {
        if (self == null) return List.of();
        try {
            return naming.selectInstances(self.serviceName(), group, true).stream()
                    .map(i -> new ServiceInstance(self.serviceName(), i.getIp(), i.getPort(),
                            i.getMetadata() == null ? Map.of() : i.getMetadata()))
                    .collect(Collectors.toList());
        } catch (Exception e) { throw new IllegalStateException("selectInstances failed", e); }
    }
}
```

- [ ] **Step 5: NacosServiceRegistryIT（环境门控）** —— 照现有 NacosControlPlane 冒烟 IT 的门控写法(`@EnabledIfEnvironmentVariable(named="CUSTOS_NACOS_IT", matches="1")` 或仓库实际用的环境变量名,**先 Read 那个 IT 确认**)。逻辑:连真 Nacos(env 给 serverAddr/账号)→ register → `peers()` 含 self → deregister → peers 不含。默认门禁此 IT 跳过(无 env)。

- [ ] **Step 6: 跑确认通过** — Run: `mvn --% -pl authz test -Dtest=NoOpServiceRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: PASS;`mvn -pl authz test-compile` 确认 NacosServiceRegistry/IT 编译过(NamingService API 对)。

- [ ] **Step 7: Commit**
```bash
git add authz/src/main/java/io/custos/authz/ServiceInstance.java authz/src/main/java/io/custos/authz/ServiceRegistry.java authz/src/main/java/io/custos/authz/NoOpServiceRegistry.java authz/src/main/java/io/custos/authz/NacosServiceRegistry.java authz/src/test/java/io/custos/authz/NoOpServiceRegistryTest.java authz/src/test/java/io/custos/authz/NacosServiceRegistryIT.java
git commit -m "feat(authz): ServiceRegistry SPI with Nacos NamingService + NoOp impls"
```

---

### Task 2: authz — TenantPdpRouter + DecisionRequest 域重载

**Files:**
- Create: `authz/src/main/java/io/custos/authz/TenantPdpRouter.java`
- Modify: `authz/src/main/java/io/custos/authz/DecisionRequest.java`（加 `of(sub,dom,obj,act)` 重载）
- Test: `authz/src/test/java/io/custos/authz/TenantPdpRouterTest.java`

- [ ] **Step 1: DecisionRequest 加域重载**
```java
// 在现有 of(sub,obj,act) 旁加:
public static DecisionRequest of(String sub, String dom, String obj, String act) {
    return new DecisionRequest(sub, dom, obj, act, RequestContext.empty());
}
```

- [ ] **Step 2: 写失败单测 TenantPdpRouterTest**

先 Read `CasbinPdp.java` 看 `reload(String policyCsv)` 与 `decide` 用法、`Decision` 的 `allowed()`/`effect()`。两租户两策略验隔离:
```java
package io.custos.authz;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TenantPdpRouterTest {
    private CasbinPdp pdpWith(String csv) { CasbinPdp p = new CasbinPdp(); p.reload(csv); return p; }

    @Test void routesByDomainAndIsolates() {
        // 租户 A:agent:x 在 dom=A 可读 tool:t;租户 B:无此策略
        CasbinPdp a = pdpWith("p, agent:x, A, tool:t, read");
        CasbinPdp b = pdpWith("");                  // 空策略 = 默认拒
        CasbinPdp denyAll = pdpWith("");
        TenantPdpRouter router = new TenantPdpRouter(denyAll);
        router.register("A", a);
        router.register("B", b);

        assertTrue(router.decide(DecisionRequest.of("agent:x", "A", "tool:t", "read")).allowed());  // A 放行
        assertFalse(router.decide(DecisionRequest.of("agent:x", "B", "tool:t", "read")).allowed()); // B 无策略→拒
        assertFalse(router.decide(DecisionRequest.of("agent:x", "GHOST", "tool:t", "read")).allowed()); // 未配置租户→denyAll 拒
    }

    @Test void reloadOneTenantDoesNotAffectOther() {
        CasbinPdp a = pdpWith("p, agent:x, A, tool:t, read");
        CasbinPdp b = pdpWith("p, agent:y, B, tool:t, read");
        TenantPdpRouter router = new TenantPdpRouter(pdpWith(""));
        router.register("A", a); router.register("B", b);
        a.reload("");                               // 撤销 A 的策略
        assertFalse(router.decide(DecisionRequest.of("agent:x", "A", "tool:t", "read")).allowed()); // A 现拒
        assertTrue(router.decide(DecisionRequest.of("agent:y", "B", "tool:t", "read")).allowed());  // B 不受影响
    }
}
```
> CSV 行格式以 CasbinPdp 模型实际为准(`p, sub, dom, obj, act`)——先 Read CasbinPdp 的 model 定义确认列顺序与 reload 解析。

- [ ] **Step 3: 跑确认失败** — Run: `mvn --% -pl authz test -Dtest=TenantPdpRouterTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 编译失败

- [ ] **Step 4: 写 TenantPdpRouter**
```java
package io.custos.authz;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 RBAC domain(=tenant)路由到各租户独立 PDP;未配置租户落 denyAll(防隔离逃逸)。线程安全。 */
public final class TenantPdpRouter implements Pdp {
    private final Map<String, Pdp> byTenant = new ConcurrentHashMap<>();
    private final Pdp denyAll;
    public TenantPdpRouter(Pdp denyAll) { this.denyAll = denyAll; }
    public void register(String tenant, Pdp pdp) { byTenant.put(tenant, pdp); }
    @Override public Decision decide(DecisionRequest req) {
        return byTenant.getOrDefault(req.dom(), denyAll).decide(req);
    }
}
```

- [ ] **Step 5: 跑确认通过** — Run: `mvn --% -pl authz test -Dtest=TenantPdpRouterTest,NoOpServiceRegistryTest -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全 PASS

- [ ] **Step 6: Commit**
```bash
git add authz/src/main/java/io/custos/authz/TenantPdpRouter.java authz/src/main/java/io/custos/authz/DecisionRequest.java authz/src/test/java/io/custos/authz/TenantPdpRouterTest.java
git commit -m "feat(authz): TenantPdpRouter routing by domain with deny-all fallback"
```

---

### Task 3: broker — QueryIntent tenant + BrokerService domain

**Files:**
- Modify: `broker/src/main/java/io/custos/broker/QueryIntent.java`（加 tenant，默认 `default`）
- Modify: `broker/src/main/java/io/custos/broker/BrokerService.java`（decide 传 domain=tenant）
- Modify: `broker/src/main/java/io/custos/broker/McpQueryToolServer.java`（tenant 入参）

- [ ] **Step 1: QueryIntent 加 tenant（默认 default，保留旧构造器）**

先 Read 现有 `QueryIntent.java`(M20 后是 `record(tool,resource,role,sql,approvalId)` + 便捷构造)。改:
```java
package io.custos.broker;
/** 查询意图。tenant 路由到对应租户 namespace 的策略(默认 "default"=现有单租户,向后兼容)。 */
public record QueryIntent(String tool, String resource, String role, String sql, String approvalId, String tenant) {
    public QueryIntent(String tool, String resource, String role, String sql, String approvalId) {
        this(tool, resource, role, sql, approvalId, "default");
    }
    public QueryIntent(String tool, String resource, String role, String sql) {
        this(tool, resource, role, sql, null, "default");
    }
    public QueryIntent(String tool, String resource, String sql) {
        this(tool, resource, "read-only", sql, null, "default");
    }
}
```
> 现有调用方(3/4/5 参)默认 tenant="default",dom 不变,向后兼容。

- [ ] **Step 2: BrokerService 传 domain=tenant** —— `queryDb`(line 53)的 `Decision d = pdp.decide(DecisionRequest.of(sub, obj, "read"));` 改为:
```java
Decision d = pdp.decide(DecisionRequest.of(sub, intent.tenant(), obj, "read"));
```
(用 Task 2 加的 `of(sub,dom,obj,act)` 重载。其余逻辑/审批/审计不变。)

- [ ] **Step 3: McpQueryToolServer 加 tenant 入参** —— 先 Read 现有 McpQueryToolServer(M20 已加 approvalId)。inputSchema properties 加可选 `tenant`(type string,不进 required);handle 构造 QueryIntent 时透传 `(String)args.getOrDefault("tenant","default")` 作第 6 参。以实际 builder 代码适配。

- [ ] **Step 4: 跑 broker verify** — Run: `mvn --% -pl broker -am verify -Dsurefire.failIfNoSpecifiedTests=false` Expected: 全绿。现有 BrokerServiceIT/审批流/Metrics IT 走默认 tenant="default"→dom="default"(与原 of(sub,obj,act) 一致),策略不变仍绿。若某测试直接 `new QueryIntent(...)` 旧构造,默认 tenant 已补,无需改。

- [ ] **Step 5: Commit**
```bash
git add broker/src/main/java/io/custos/broker/QueryIntent.java broker/src/main/java/io/custos/broker/BrokerService.java broker/src/main/java/io/custos/broker/McpQueryToolServer.java
git commit -m "feat(broker): QueryIntent tenant routes decision domain to per-tenant policy"
```

---

### Task 4: app — tenant 配置 + HostConfig 重构（TenantPdpRouter + ServiceRegistry）+ /cluster

**Files:**
- Modify: `app/src/main/java/io/custos/app/config/CustosProperties.java`（加 Tenant/Cluster）
- Modify: `app/src/main/java/io/custos/app/config/HostConfig.java`（pdp→TenantPdpRouter + serviceRegistry bean）
- Create: `app/src/main/java/io/custos/app/cluster/ServiceLifecycle.java`
- Create: `app/src/main/java/io/custos/app/cluster/ClusterController.java`
- Modify: `app/src/main/java/io/custos/app/security/AdminTokenFilter.java`（门控 `/cluster`）
- Modify: `app/src/main/java/io/custos/app/query/QueryController.java`（tenant 透传）
- Test: `app/src/test/java/io/custos/app/MultiTenantPolicyIT.java`

- [ ] **Step 1: CustosProperties 加 Tenant + Cluster**

仿现有嵌套类(Engine/Nacos/...)风格加:
```java
private java.util.List<Tenant> tenants = new java.util.ArrayList<>();
public java.util.List<Tenant> getTenants() { return tenants; }
public void setTenants(java.util.List<Tenant> v) { this.tenants = v; }
private Cluster cluster = new Cluster();
public Cluster getCluster() { return cluster; }
public void setCluster(Cluster v) { this.cluster = v; }

public static class Tenant {
    private String name; private String namespace;
    public Tenant() {}
    public Tenant(String name, String namespace) { this.name = name; this.namespace = namespace; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getNamespace() { return namespace; } public void setNamespace(String v) { namespace = v; }
}
public static class Cluster {
    private String serviceName = "custos-host"; private boolean register = true;
    public String getServiceName() { return serviceName; } public void setServiceName(String v) { serviceName = v; }
    public boolean isRegister() { return register; } public void setRegister(boolean v) { register = v; }
}
```

- [ ] **Step 2: HostConfig 重构 Pdp 为 TenantPdpRouter + serviceRegistry**

先 Read 完整 `HostConfig.java`。改动:
- **保留** `controlPlane(CustosProperties props)` bean(默认租户 CP,namespace=nacos.namespace)——PolicyService 仍用它(get/put 默认租户策略),且 InMemory 模式下与默认租户 watcher 共享同一实例(状态共享,policy PUT 能被 watcher 看到)。
- **删除** `casbinPdp()` 与 `policyWatcher()` 两个 bean,改为一个 `pdp` bean 构建 TenantPdpRouter:
```java
@Bean
public io.custos.authz.Pdp pdp(CustosProperties props, ControlPlane defaultControlPlane) {
    CasbinPdp denyAll = new CasbinPdp(); denyAll.reload("");        // 未配置租户落此(默认拒)
    io.custos.authz.TenantPdpRouter router = new io.custos.authz.TenantPdpRouter(denyAll);
    java.util.List<CustosProperties.Tenant> tenants = props.getTenants();
    if (tenants == null || tenants.isEmpty())
        tenants = java.util.List.of(new CustosProperties.Tenant("default", props.getNacos().getNamespace()));
    for (CustosProperties.Tenant t : tenants) {
        ControlPlane cp = "default".equals(t.getName())
                ? defaultControlPlane                               // 默认租户复用 bean(与 PolicyService 共享)
                : buildControlPlane(props, t.getNamespace());       // 其它租户独立 CP(各自 namespace)
        CasbinPdp tpdp = new CasbinPdp();
        PolicyWatcher w = new PolicyWatcher(cp, props.getNacos().getPolicyDataId(), tpdp);
        w.start();                                                  // 初载 + 订阅热推(方法名以现有 policyWatcher bean 调用为准)
        router.register(t.getName(), tpdp);
    }
    return router;
}

/** 复用 controlPlane bean 的构造逻辑,按 namespace 建 CP(InMemory/Nacos)。 */
private ControlPlane buildControlPlane(CustosProperties props, String namespace) {
    String addr = props.getNacos().getServerAddr();
    if (addr == null || addr.isBlank()) return new InMemoryControlPlane();
    return new NacosControlPlane(addr, namespace, props.getNacos().getGroup(),
            props.getNacos().getUsername(), props.getNacos().getPassword());   // 参数以现有 controlPlane bean 实参为准
}
```
> `controlPlane(props)` bean 内部也改为 `buildControlPlane(props, props.getNacos().getNamespace())` 复用(避免两份构造逻辑)。`PolicyWatcher` 启动方法名(`start()`/`init()`)以现有 `policyWatcher` bean 体为准。
- **改** `operatorService(...)` bean:形参 `CasbinPdp pdp` → `io.custos.authz.Pdp pdp`(注入上面的 router bean);body 不变(OperatorService 收 Pdp)。
- **加** `serviceRegistry` bean:
```java
@Bean
public io.custos.authz.ServiceRegistry serviceRegistry(CustosProperties props) {
    String addr = props.getNacos().getServerAddr();
    if (addr == null || addr.isBlank()) return new io.custos.authz.NoOpServiceRegistry();
    return new io.custos.authz.NacosServiceRegistry(addr, props.getNacos().getNamespace(),
            props.getNacos().getGroup(), props.getNacos().getUsername(), props.getNacos().getPassword());
}
```

- [ ] **Step 3: ServiceLifecycle（启动注册 / 关停注销）**
```java
package io.custos.app.cluster;

import io.custos.app.config.CustosProperties;
import io.custos.authz.ServiceInstance;
import io.custos.authz.ServiceRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** host 启动(Web 端口就绪)即注册到 ServiceRegistry,关停注销。注册元数据仅非敏感项。 */
@Component
public class ServiceLifecycle implements ApplicationListener<WebServerInitializedEvent> {
    private final ServiceRegistry registry;
    private final CustosProperties props;
    public ServiceLifecycle(ServiceRegistry registry, CustosProperties props) {
        this.registry = registry; this.props = props;
    }
    @Override public void onApplicationEvent(WebServerInitializedEvent e) {
        if (!props.getCluster().isRegister()) return;
        int port = e.getWebServer().getPort();
        registry.register(new ServiceInstance(props.getCluster().getServiceName(),
                localIp(), port, Map.of("version", "0.6", "mcp", "stdio")));
    }
    @PreDestroy public void shutdown() { registry.deregister(); }
    private String localIp() {
        try { return java.net.InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception ex) { return "127.0.0.1"; }
    }
}
```
> 不在 metadata 放 sealed/密钥等敏感态(红线)。`WebServerInitializedEvent` 给真实端口。

- [ ] **Step 4: ClusterController（/cluster/peers，admin-gated）**
```java
package io.custos.app.cluster;

import io.custos.authz.ServiceInstance;
import io.custos.authz.ServiceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** admin-gated:发现的活跃 host 列表(多 host/集群可见性)。 */
@RestController
@RequestMapping("/cluster")
public class ClusterController {
    private final ServiceRegistry registry;
    public ClusterController(ServiceRegistry registry) { this.registry = registry; }
    @GetMapping("/peers")
    public List<ServiceInstance> peers() { return registry.peers(); }
}
```

- [ ] **Step 5: AdminTokenFilter + HostConfig 门控 /cluster** —— AdminTokenFilter adminPath 加 `|| path.startsWith("/cluster")`;HostConfig `adminTokenFilter()` urlPatterns 加 `"/cluster/*"`。

- [ ] **Step 6: QueryController 透传 tenant** —— 先 Read `QueryController.java`,构造 QueryIntent 时加第 6 参 `body.getOrDefault("tenant","default")`(以实际 body 取值方式适配)。

- [ ] **Step 7: 写 MultiTenantPolicyIT**

照 `ConsoleReadEndpointsIT` 模板(Spring Boot Test + Testcontainers + FixedTokenConfig + 永不抛异常 RestTemplate + seed 建 5 表 + init/unseal)。**默认单租户配置**(InMemory,server-addr 空)下:
- `GET /cluster/peers` 无 token → 401;带 admin token → 200(NoOp 下含 self,或解封后 register 已发生——注意 register 在 WebServerInitializedEvent,测试 RANDOM_PORT 下应已触发;断言返回是 JSON 数组即可,不强求 self 一定在)。
- 经一次真实 query_db(照 ConsoleReadEndpointsIT 注册 appdb + 写默认租户策略 + ES256 token):tenant 缺省("default")→ 命中默认策略 ALLOWED;**带 tenant="ghost"(未配置)→ DENIED**(路由到 denyAll,证明隔离闸)。
最小必须覆盖:① /cluster/peers 401;② tenant=default 放行 vs tenant=ghost 拒。

- [ ] **Step 8: 跑 app verify** — Run: `mvn --% -pl app -am verify -Dsurefire.failIfNoSpecifiedTests=false`(先 `mvn -pl authz,broker -am install -DskipTests` 装上游)Expected: 全绿(MultiTenantPolicyIT + 现有 ConsoleReadEndpointsIT/ApprovalFlowIT/MetricsEndpointIT/HostEndToEndIT 回归)。

- [ ] **Step 9: Commit**
```bash
git add app/
git commit -m "feat(app): multi-tenant PDP routing + Nacos service registration + /cluster/peers"
```

---

### Task 5: compose/demo + 看板 + 全量门禁 + 合并

**Files:**
- Modify: `examples/docker-compose.yml`（custos 环境可选加 tenants 示例 + 注释;可选 custos-2）
- Modify: `examples/demo.md`（Nacos 服务注册可见 + 多租户隔离 AC）
- Modify: `docs/spec/module/M18-nacos-deep.md`（status planned→done + subtasks/锚）
- 产物:`docs/cockpit.html` 重渲

- [ ] **Step 1: demo.md 加 Nacos 深接段** —— 加「Nacos 深接(M18)」:① `docker compose up` 后 custos-host 注册进 Nacos——`curl -H "Authorization: Bearer <admin>" localhost:8080/cluster/peers` 见活跃 host(无 token 401),或经 Nacos 控制台/naming API 看 `custos-host` 服务;② 多租户隔离——配置 `custos.tenants`(默认 default + 一个 tenant-a),带 `tenant` 入参的 query_db 按各自 namespace 策略决策(tenant=default 放行、未配置 tenant 拒)。命令风格对齐现有 demo。说明真 namespace 隔离需在 Nacos 预建对应 namespace。

- [ ] **Step 2: docker-compose（可选 tenants 注释）** —— 在 custos 服务 environment 加注释示例(默认单租户 default;如何配多租户经 `CUSTOS_TENANTS_*` 或挂 application.yml)。**不强制**加 custos-2(spec §7 说明:多 host 发现可文档化为部署说明,避免 compose 复杂度)。若加 custos-2 展示发现,确保两 host 共享同 MySQL/Nacos(承接 CLAUDE.md 多 host 共享存储说明)。本步以「能跑、文档清楚」为准。

- [ ] **Step 3: 全量门禁** — Run: `mvn -B clean verify` Expected: BUILD SUCCESS(authz 新单测 + broker/app 契约回归 + MultiTenantPolicyIT;env-gated 的真 Nacos IT 跳过)。

- [ ] **Step 4: M18 看板卡 done** —— Read `docs/spec/module/M18-nacos-deep.md`(planned)+ 一张 done 卡学风格。改 status→done、progress→100,加 subtasks S1-S5 + code 锚指真实文件(authz/.../ServiceRegistry.java、authz/.../TenantPdpRouter.java、broker/.../QueryIntent.java、app/.../cluster/ClusterController.java、examples/demo.md)+ doc 锚指 spec(`path#子串`,**先 Read spec 确认命中 `## ` 标题**,如 `#架构与组件`/`#数据流`/`#配置`)。写完用 state.json 验证无 `heading not found`。

- [ ] **Step 5: 重渲 + lint** — Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml` 然后 `python3 -m docs_cockpit lint --config docs-cockpit.yaml` Expected: 0 error/0 warning;state.json M18 doc_anchors 无 `heading not found`。

- [ ] **Step 6: Commit + 合并 main + push**
```bash
git add examples/docker-compose.yml examples/demo.md docs/spec/module/M18-nacos-deep.md docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "docs: M18 Nacos deep — demo service registration + multi-tenant + dashboard card"
git checkout main
git merge --ff-only impl/m18-nacos-deep
git push origin main
```
> FF 前注意 **main 可能被 Codex 并发推进**:`git fetch origin main` + `git merge-base --is-ancestor main impl/m18-nacos-deep` 为真才 ff-only。**若 `--ff-only` 失败(main 分叉),停下如实报告,别强推/rebase/merge commit**——交回上层处理。

---

## Self-Review

**1. Spec 覆盖:** §1 D1(ServiceRegistry Nacos/NoOp)→Task1;D2(TenantPdpRouter 每租户 namespace)→Task2/4;D3(tenant=domain 默认 default)→Task3;D4(router 在 AbacPdp 内层——注:默认 host 用 CasbinPdp 直挂,router 替换它,语义一致)→Task4;D5(真 Nacos env-gated)→Task1 IT/Task4 IT 用 InMemory。§2 组件→Task1-4 逐一。§3 数据流(注册/发现 + 多租户决策 + denyAll 安全闸)→Task1/2/4。§4 契约(QueryIntent tenant + TenantPdpRouter)→Task2/3。§5 配置→Task4。§6 测试→各单测/IT。§7 交付物→Task1-5。§8 验收→Task5 门禁。§9 YAGNI(不做 MCP registry 推送/A2A/per-tenant seal/租户 CRUD/请求级 tenant 鉴权/namespace 自动创建)→遵守。

**2. 占位符扫描:** ServiceInstance/ServiceRegistry/NoOp/NacosServiceRegistry/TenantPdpRouter/QueryIntent/ServiceLifecycle/ClusterController 给完整代码;HostConfig 重构给目标 bean 形状 + buildControlPlane 复用。"先 Read 确认"处(NamingService API 版本、CasbinPdp model 列序/reload、PolicyWatcher 启动方法名、controlPlane bean 的 username/password 实参、现有 NacosControlPlane IT 的门控变量名、QueryController body 取值)为真实核准动作非占位。

**3. 类型一致:** `ServiceRegistry`(register/deregister/peers)跨 NoOp/Nacos/ServiceLifecycle/ClusterController 一致;`ServiceInstance(serviceName,ip,port,metadata)` 一致;`TenantPdpRouter(Pdp denyAll)+register+decide` 与 HostConfig pdp bean、TenantPdpRouterTest 一致;`DecisionRequest.of(sub,dom,obj,act)` 与 BrokerService 调用、TenantPdpRouterTest 一致;`QueryIntent(tool,resource,role,sql,approvalId,tenant)` 默认 tenant="default" 与旧构造器、BrokerService.intent.tenant()、QueryController/MCP 透传一致;`Pdp` 注入 OperatorService(router 替 CasbinPdp,都是 Pdp)一致;默认租户名 "default" 跨 QueryIntent 默认/HostConfig tenants 缺省/DecisionRequest.of dom 一致(向后兼容)。
