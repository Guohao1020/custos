---
id: M18
title: Nacos 深接（服务注册发现 · namespace 多租户 PDP 路由）
status: done
sprint: v0.6
progress: 100
manualProgress: false
desc: "把 Nacos 从'一个 policy 配置热推'升为统一控制+发现平面，本轮聚焦两件可对真 Nacos 3.2 验证的高价值能力：① 服务注册发现——authz `ServiceRegistry` SPI（NacosServiceRegistry NamingService / NoOp 双实现，沿用 server-addr 空→NoOp），custos-host 启动经 WebServerInitializedEvent 注册为服务实例、关停注销，admin-gated `GET /cluster/peers` 看活跃 host；② namespace 多租户真隔离——`TenantPdpRouter implements Pdp` 按 RBAC domain=tenant 路由到各租户独立 CasbinPdp+PolicyWatcher（独立 Nacos namespace 物理隔离策略，秒级热推互不影响），未配置 tenant 一律落 denyAll（防隔离逃逸）。broker `QueryIntent` 加 tenant（默认 default，向后兼容），REST/MCP query_db 透传 tenant 入参。server-addr 空时降级单节点 NoOp + InMemory 单租户 default，现有 demo AC 全过。MCP registry 推送/tool.annotations 元数据富化按 YAGNI 留到能真验证时。"
docs:
  - { title: "Nacos 深接设计 spec", path: "docs/superpowers/specs/2026-06-12-nacos-deep-design.md" }
  - { title: "Nacos 深接实现计划", path: "docs/superpowers/plans/2026-06-12-nacos-deep.md" }
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "Nacos AI 中心杠杆点研究", path: "docs/research/nacos-ai-center.md" }
subtasks:
  - id: M18-S1
    title: "authz ServiceRegistry SPI：NacosServiceRegistry（NamingService register/selectInstances/deregister）+ NoOpServiceRegistry（server-addr 空降级，peers 仅 self）+ ServiceInstance record"
    done: true
    code:
      - authz/src/main/java/io/custos/authz/ServiceRegistry.java
      - authz/src/main/java/io/custos/authz/ServiceInstance.java
      - authz/src/main/java/io/custos/authz/NacosServiceRegistry.java
      - authz/src/main/java/io/custos/authz/NoOpServiceRegistry.java
      - authz/src/test/java/io/custos/authz/NoOpServiceRegistryTest.java
    docs:
      - docs/superpowers/specs/2026-06-12-nacos-deep-design.md#架构与组件
  - id: M18-S2
    title: "authz TenantPdpRouter implements Pdp：按 RBAC domain=tenant 路由到各租户独立 PDP，未配置 tenant 落 denyAll（隔离安全闸）+ DecisionRequest.of(sub,dom,obj,act) 域重载"
    done: true
    code:
      - authz/src/main/java/io/custos/authz/TenantPdpRouter.java
      - authz/src/main/java/io/custos/authz/DecisionRequest.java
      - authz/src/test/java/io/custos/authz/TenantPdpRouterTest.java
    docs:
      - docs/superpowers/specs/2026-06-12-nacos-deep-design.md#数据流
  - id: M18-S3
    title: "broker QueryIntent 加 tenant（默认 default，保留旧构造器向后兼容）+ BrokerService decide 传 domain=tenant + McpQueryToolServer tenant 入参"
    done: true
    code:
      - broker/src/main/java/io/custos/broker/QueryIntent.java
      - broker/src/main/java/io/custos/broker/BrokerService.java
      - broker/src/main/java/io/custos/broker/McpQueryToolServer.java
    docs:
      - docs/superpowers/specs/2026-06-12-nacos-deep-design.md#契约变更
  - id: M18-S4
    title: "app HostConfig 把单 Pdp 重构为 TenantPdpRouter（每租户 ControlPlane+CasbinPdp+PolicyWatcher）+ ServiceRegistry bean + ServiceLifecycle（启动注册/关停注销）+ ClusterController /cluster/peers（admin-gated）+ CustosProperties.tenants/cluster + MultiTenantPolicyIT"
    done: true
    code:
      - app/src/main/java/io/custos/app/config/HostConfig.java
      - app/src/main/java/io/custos/app/config/CustosProperties.java
      - app/src/main/java/io/custos/app/cluster/ServiceLifecycle.java
      - app/src/main/java/io/custos/app/cluster/ClusterController.java
      - app/src/test/java/io/custos/app/MultiTenantPolicyIT.java
    docs:
      - docs/superpowers/specs/2026-06-12-nacos-deep-design.md#配置
  - id: M18-S5
    title: "compose 多租户配置注释 + demo runbook「Nacos 深接」段（服务注册 /cluster/peers 可见 + namespace 多租户隔离 + denyAll 安全闸）+ 看板卡 done"
    done: true
    code:
      - examples/docker-compose.yml
      - examples/demo.md
    docs:
      - docs/superpowers/specs/2026-06-12-nacos-deep-design.md#交付物
---

# M18 · Nacos 深接（服务注册发现 · namespace 多租户 PDP 路由）

承接定位主线「Nacos 发现、Custos 执行」：v0.6 前 Nacos 只干"单 namespace 单 dataId 的 policy 热推"一件事，
本模块把它升为统一控制+发现平面，聚焦两件可对真 Nacos 3.2 验证的高价值能力。

## 已交付

- **服务注册发现**：authz `ServiceRegistry` SPI（`NacosServiceRegistry` 经 NamingService register/selectInstances/deregister；`NoOpServiceRegistry` 在 server-addr 空时降级，`peers()` 仅 self）。custos-host 启动经 `WebServerInitializedEvent` 注册为 `custos-host` 服务实例（metadata 仅非敏感 version/mcp）、`@PreDestroy` 关停注销；admin-gated `GET /cluster/peers` 看发现的活跃 host（无 token → 401）。多 host 共享同 MySQL+Nacos 即注册进同一服务，撑 v0.7 集群。
- **namespace 多租户真隔离**：`TenantPdpRouter implements Pdp` 按 RBAC `domain`=tenant 路由到各租户独立 `CasbinPdp`+`PolicyWatcher`（各租户独立 Nacos namespace 物理隔离策略，秒级热推互不影响）。`QueryIntent` 加 `tenant`（默认 `default`，匹配现有 dom，向后兼容），REST `/query_db` body 与 MCP `query_db` 工具透传 `tenant` 入参。
- **denyAll 安全闸**：携带未配置 tenant 的请求一律落 denyAll（默认拒），**绝不** fallback 到 default 租户策略——否则 agent 传任意 tenant 即可逃逸隔离。
- **向后兼容**：`custos.nacos.server-addr` 空 → `NoOpServiceRegistry` + InMemory 单租户 `default`；`custos.tenants` 留空 → 单租户 default 用 `nacos.namespace`。现有 demo AC1–AC10 全过。

## 安全姿态

- 注册 metadata 只放非敏感项（version/mcp endpoint），**绝不**放 sealed 态/密钥（红线）。
- 租户隔离只到策略 namespace 层；密钥/审计链仍单实例（per-tenant seal/storage/审计按 YAGNI 不做，避免 v0.6 爆量）。
- 不做请求级 tenant 鉴权：tenant 由请求声明，能否放行由该租户 namespace 的策略决定——agent 在错误租户 namespace 无授权即被拒。

## 不做（YAGNI）

MCP server registry CRUD 推送 / `tool.annotations` 元数据富化到 Nacos AI MCP registry（需 AI-center admin API，本地真 e2e 难保证）、A2A/AgentSpec/Skill 注册（=M19/v0.7）、per-tenant 独立 seal/storage/审计链、租户 CRUD 运维 API、Nacos namespace 自动创建（由运维预建）—— 本轮均不做，留待能真验证 / 后续按需。
