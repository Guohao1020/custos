---
id: SPEC-M18-NACOS-DEEP
type: spec
title: "M18 Nacos 深接（服务注册发现 · namespace 多租户 PDP 路由）设计"
status: reviewing
date: 2026-06-12
desc: "把 Nacos 从'一个 policy 配置热推'升为统一控制+发现平面:custos-host 经 nacos-client NamingService 注册为服务实例(健康+多 host 互发现,撑 v0.7 集群)+ 每租户独立 Nacos namespace 的 TenantPdpRouter(各租户独立 PDP+PolicyWatcher 热推,tenant 作 RBAC domain,真隔离)。"
---

# M18 Nacos 深接（Nacos Deep Integration）设计

## 0 · 背景与动机

承接定位主线「Nacos 发现、Custos 执行」。当前 Nacos 只干一件事:单 namespace 单 dataId 的 policy 热推(`NacosControlPlane`+`PolicyWatcher`+单 `CasbinPdp`)。M18 把它升为统一控制+发现平面,做两件高价值、可对真 Nacos 3.2 验证的事:① **服务注册发现**——custos-host 注册为 Nacos 服务实例,多 host 互相发现(直接撑 v0.7 集群);② **namespace 多租户真隔离**——每租户一个 Nacos namespace,各自的策略加载到独立 PDP(独立 PolicyWatcher 热推),请求按 tenant 路由,tenant 即 M08 的 RBAC domain。范围依用户核准聚焦此两项,不含需 AI-center admin API 的 MCP registry 推送(留到能真验证时)。

## 1 · 已确认的关键决策

| # | 决策 | 取舍 |
|---|---|---|
| D1 | **`ServiceRegistry` SPI(NacosServiceRegistry/NoOp 双实现)** | 沿用 `ControlPlane` 的 server-addr 空→InMemory/NoOp、非空→Nacos 模式;NamingService 与 ConfigService 同属 nacos-client,零新增依赖 |
| D2 | **每租户独立 Nacos namespace + `TenantPdpRouter`** | 真 namespace 隔离(各租户策略物理隔离在不同 namespace);各租户独立 CasbinPdp+PolicyWatcher,秒级热推互不影响 |
| D3 | **tenant 即 RBAC domain,请求显式携带(默认 `default`)** | 复用 M08 的 `r=sub,dom,obj,act`;`QueryIntent`/MCP query_db 加可选 tenant 入参,**默认值 `default`** 以匹配现有 `DecisionRequest.of` 的 dom="default" 与既有策略/测试,严格向后兼容(不可用 public,否则现有 dom="default" 策略全失配) |
| D4 | **TenantPdpRouter 在 AbacPdp 内层** | `AbacPdp(TenantPdpRouter(...), risk, hook, policy)`——租户路由选中该租户 CasbinPdp,ABAC 三态(风险/审批)在外层统一,无需每租户复制 ABAC 逻辑 |
| D5 | **真 Nacos 测试环境门控,默认门禁用 InMemory** | 沿用现有 NacosControlPlane「环境门控冒烟 IT」;TenantPdpRouter 路由/隔离逻辑用 InMemoryControlPlane 单测(默认门禁绿),真 namespace 隔离经 compose/demo + env-gated IT 验证 |

## 2 · 架构与组件

```
authz                                      broker                        app
 ServiceRegistry (SPI)                      QueryIntent + tenant          CustosProperties.tenants[] (name→namespace)
  register(self)/deregister/peers()         BrokerService:               HostConfig:
 ServiceInstance(name,ip,port,meta)          decide(req.dom=tenant)        ├ 建 TenantPdpRouter(每租户 NacosControlPlane
 NacosServiceRegistry (NamingService)                                      │  +CasbinPdp+PolicyWatcher)→ 外包 AbacPdp
 NoOpServiceRegistry                                                       ├ 建 ServiceRegistry(Nacos/NoOp)
 TenantPdpRouter implements Pdp                                            │  → 启动注册 self / 关停 deregister
  Map<tenant, Pdp> + default                                              └ ClusterController GET /cluster/peers (admin)
  decide(req)→byTenant[req.dom()].decide   QueryController/MCP: tenant 入参 → QueryIntent
```

- **authz**:
  - `ServiceInstance`(record:serviceName, ip, port, Map<String,String> metadata)。
  - `ServiceRegistry`(接口:`void register(ServiceInstance self)` / `void deregister()` / `List<ServiceInstance> peers()`)。
  - `NacosServiceRegistry`(NamingService:`NacosFactory.createNamingService(props)`,props 同 NacosControlPlane 的 serverAddr/namespace/username/password;register→`registerInstance(name,group,Instance)`、peers→`selectInstances(name,group,true)`、deregister→`deregisterInstance`)。
  - `NoOpServiceRegistry`(server-addr 空时:register/deregister 空操作,peers 返回仅含 self 的单元素列表)。
  - `TenantPdpRouter implements Pdp`:持 `Map<String,Pdp> byTenant` + `Pdp fallback`(默认租户);`decide(req)` 按 `req.dom()` 选 PDP(无匹配→fallback)。提供 `register(String tenant, Pdp pdp)`。
- **broker**:`QueryIntent` 加 `tenant`(默认 `public`);`BrokerService.queryDb` 用 `new DecisionRequest(sub, intent.tenant(), obj, "read", ctx)`(domain=tenant)。审批重发路径不变(approvalId 跳过 PDP)。
- **app**:`CustosProperties` 加 `tenants`(列表:name+namespace;空则默认单租户 `public`→`nacos.namespace`)。`HostConfig` 把现有单 Pdp bean 改为:按 tenants 列表逐个建 `NacosControlPlane(该 namespace)`+`CasbinPdp`+`PolicyWatcher`,装进 `TenantPdpRouter`,外层包 `AbacPdp`(风险/审批配置不变);建 `ServiceRegistry` bean。`ServiceLifecycle`(ApplicationRunner/@PreDestroy)启动注册 self、关停 deregister。`ClusterController` `GET /cluster/peers`(admin-gated)。`QueryController`/MCP query_db 透传 tenant。

## 3 · 数据流

**服务注册/发现**:host 启动 → `registry.register(ServiceInstance("custos-host", localIp, 8080, {version, mcpEndpoint, sealed}))` → Nacos naming 持有(心跳保活)。运维/peer `GET /cluster/peers` → `registry.peers()` → `selectInstances("custos-host", healthyOnly=true)` 返回活跃 host 列表。关停 → `deregister()`。(server-addr 空 → NoOp,peers 仅 self。)

**多租户决策**:agent query 带 tenant=A → broker `decide(DecisionRequest(sub, "A", obj, "read", ctx))` → `TenantPdpRouter` 按 dom="A" 选中租户 A 的 CasbinPdp(加载自 namespace_A 的策略)→ 命中/拒绝;tenant=B 走租户 B 的 PDP(namespace_B 策略)。两租户策略物理隔离在不同 Nacos namespace,各自 PolicyWatcher 独立热推(改 A 的策略不影响 B)。

**fallback 语义(安全关键)**:`decide(req) = byTenant.getOrDefault(req.dom(), denyAll).decide(req)`。
- **未携带 tenant** → `QueryIntent` 默认值 `default`,命中**已配置的 `default` 租户** PDP(加载自 `nacos.namespace`,即现有单租户行为,向后兼容)。
- **携带了未配置的 tenant** → 命中 `denyAll`(默认拒,空策略 CasbinPdp 或恒 deny),**绝不** fallback 到 default 租户策略——否则 agent 传任意 tenant 即可逃逸隔离。这是隔离的安全闸。

## 4 · 契约变更（最小）

```java
// broker：QueryIntent 加 tenant(默认 "default"——匹配现有 dom),保留旧构造器
public record QueryIntent(String tool, String resource, String role, String sql, String approvalId, String tenant) {
    // 旧 5 参/4 参/3 参构造器默认 tenant="default"(与 DecisionRequest.of 的 dom="default" 一致,向后兼容)
}
// authz：Pdp 路由(fallback = denyAll,未配置租户一律拒,防隔离逃逸)
public final class TenantPdpRouter implements Pdp {
    public TenantPdpRouter(Pdp denyAll) {...}              // denyAll:空策略 CasbinPdp 或恒 deny 的 Pdp
    public void register(String tenant, Pdp pdp) {...}
    public Decision decide(DecisionRequest req) { return byTenant.getOrDefault(req.dom(), denyAll).decide(req); }
}
```
- MCP query_db 加可选 `tenant` 入参(默认 public);QueryController body 取 tenant;现有调用方不传则 public,向后兼容。
- `DecisionRequest.of(sub,obj,act)` 保留(默认 dom);broker 改走带 domain 的构造(以现有 DecisionRequest 构造器/RequestContext 默认值为准,必要时加 `of(sub,dom,obj,act)` 重载)。

## 5 · 配置

```yaml
custos:
  nacos:
    server-addr: ""          # 空→InMemoryControlPlane + NoOpServiceRegistry(单节点)
    namespace: public
    policy-data-id: custos-policy
    group: DEFAULT_GROUP
  tenants:                    # 空→单租户 name=default 用 nacos.namespace(向后兼容,broker dom=default)
    - name: default           # 默认租户:名字必须是 default(匹配 QueryIntent 默认 + DecisionRequest.of dom)
      namespace: public
    - name: tenant-a          # 额外租户示例:独立 namespace 隔离
      namespace: ns-tenant-a
  cluster:
    service-name: custos-host
    register: true           # server-addr 空时即便 true 也走 NoOp
```

## 6 · 测试（TDD)

- **authz `TenantPdpRouterTest`**(纯单元,InMemoryControlPlane):两租户两策略,租户 A 的 agent 对 tool 放行、同 agent 以租户 B 决策被拒(隔离);未知 tenant 走 fallback;改租户 A 策略不影响租户 B(独立 reload)。
- **authz `NoOpServiceRegistryTest`**(纯单元):register/deregister 不抛,peers 返回仅 self。
- **authz `NacosServiceRegistryIT`**(**环境门控**,照现有 NacosControlPlane 冒烟 IT 的 `@EnabledIfEnvironmentVariable` 模式):对真 Nacos register→selectInstances 见到 self→deregister 后消失。默认门禁跳过。
- **broker 回归**:QueryIntent 加 tenant 后,BrokerServiceIT/审批流/Metrics IT 随契约更新仍绿(默认 public,decide 走单租户)。可加一条:两租户路由的 broker 级断言(用 TenantPdpRouter 装两租户 InMemory 策略)。
- **app `MultiTenantPolicyIT`**(Testcontainers,InMemory 控制面或种入两租户内存策略):带 tenant=A 的 query 放行、tenant=B 同请求被拒;`GET /cluster/peers` 无 token→401,带 token→200(NoOp 下含 self)。
- **全量门禁** `mvn -B clean verify` 绿(env-gated 的真 Nacos IT 不计入)。

## 7 · 交付物

- authz:`ServiceInstance`/`ServiceRegistry`/`NacosServiceRegistry`/`NoOpServiceRegistry`;`TenantPdpRouter`;`TenantPdpRouterTest`/`NoOpServiceRegistryTest`/`NacosServiceRegistryIT`。
- broker:`QueryIntent` 加 tenant;`BrokerService` 传 domain。
- app:`CustosProperties.tenants`+`cluster`;`HostConfig` 建 TenantPdpRouter(每租户 ControlPlane+CasbinPdp+PolicyWatcher)+ ServiceRegistry;`ServiceLifecycle`(启动注册/关停注销);`ClusterController` `/cluster/peers`(admin-gated,AdminTokenFilter+HostConfig 加 `/cluster`);`QueryController`/MCP 透传 tenant;`MultiTenantPolicyIT`。
- compose/demo:`examples/demo.md` 加 ① 查 Nacos naming 见 custos-host 注册(curl Nacos `/nacos/v3/...` 或控制台)② 两租户策略隔离(tenant=A 放行、tenant=B 拒)。compose 可选加 `custos-2`(同存储/Nacos,展示多 host 发现)——若复杂度高则文档化为多 host 部署说明,不强制进 compose。
- M18 看板卡 done。

## 8 · 验收标准

- `docker compose up` 后:custos-host 出现在 Nacos 服务列表(naming API/控制台可见);`GET /cluster/peers` 带 admin token 返回含 self 的活跃 host 列表,无 token→401。
- 配置两租户(public + tenant-a,各自 namespace),带 tenant=public 的 query 与带 tenant=tenant-a 的同 agent 请求按各自 namespace 策略分别决策,互不串(改一个租户策略不影响另一个)。
- server-addr 空时单节点正常(NoOp registry + InMemory 单租户),向后兼容,现有 demo AC 全过。
- broker 不变审计/secretless 语义;`mvn -B clean verify` 全绿(含新单测/IT + 契约回归)。

## 9 · 不做（YAGNI)

- 不做 MCP server registry CRUD 推送 / tool.annotations 元数据富化到 Nacos AI MCP registry(需 AI-center admin API,本地真 e2e 难保证)——留到能真验证时。
- 不做 A2A/AgentSpec/Skill 注册(=M19/v0.7)。
- 不做 per-tenant 独立 seal/storage/审计链(租户隔离只到策略 namespace 层;密钥/审计仍单实例)——避免 v0.6 爆量。
- 不做租户 CRUD 运维 API(租户经配置声明;动态租户管理留后)。
- 不做请求级 tenant 鉴权(tenant 由请求声明 + 该租户策略决定能否;防伪 tenant 靠策略本身——agent 在错误租户 namespace 无授权即被拒)。
- 不把真 Nacos namespace 自动创建纳入 host(namespace 由运维在 Nacos 预建;host 只连)。
