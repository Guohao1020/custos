# Nacos 日常开发参考文档

> 面向 Java / Spring Cloud + K8s 团队的实用速查手册。基于 Nacos 3.x（兼容 2.x），覆盖核心概念、服务发现、配置中心、Spring Cloud 集成、常用 API、AI 管理中心、SDK 与运维排障。
>
> 校订于 2026-06。具体端口/版本/参数以落地时官方文档与实际环境为准。

---

## 目录

1. [Nacos 是什么](#1-nacos-是什么)
2. [核心概念与数据模型](#2-核心概念与数据模型)
3. [架构与一致性协议](#3-架构与一致性协议)
4. [端口速查](#4-端口速查)
5. [服务发现与注册](#5-服务发现与注册)
6. [配置中心](#6-配置中心)
7. [Spring Cloud / Spring Boot 集成](#7-spring-cloud--spring-boot-集成)
8. [常用 OpenAPI 速查](#8-常用-openapi-速查)
9. [鉴权与多环境隔离](#9-鉴权与多环境隔离)
10. [AI 管理中心（MCP Registry）](#10-ai-管理中心mcp-registry)
11. [SDK 速查](#11-sdk-速查)
12. [运维手册](#12-运维手册)
13. [常见问题排查](#13-常见问题排查)
14. [最佳实践清单](#14-最佳实践清单)
15. [参考资料](#15-参考资料)

---

## 1. Nacos 是什么

Nacos（**Na**ming **a**nd **Co**nfiguration **S**ervice）是阿里巴巴开源的**动态服务发现、配置管理和服务管理平台**。一句话：它同时是「注册中心」+「配置中心」，并在 3.x 起扩展为面向云原生 AI 应用的能力中枢。

核心能力四件套：

- **服务发现与健康检查**：服务注册、发现、健康探测、负载均衡。
- **动态配置管理**：集中化、外部化配置，运行时热更新。
- **动态 DNS 服务**：权重路由、基于 DNS 的服务发现。
- **服务及元数据管理**：从服务视角管理元数据、生命周期、流量。

3.x 新增：**MCP Registry**（AI 工具注册与发现）、安全零信任、控制台/Admin API 重构。

---

## 2. 核心概念与数据模型

Nacos 的所有资源（配置、服务）都由一个**三元组**唯一定位：

```
Namespace  +  Group  +  (DataId | ServiceName)
```

| 概念 | 说明 | 默认值 | 典型用法 |
|------|------|--------|----------|
| **Namespace** | 命名空间，最高层隔离单元 | `public`（空串） | 隔离环境（dev/test/prod）或团队/租户 |
| **Group** | 分组 | `DEFAULT_GROUP` | 区分子系统、业务线或环境 |
| **DataId** | 配置的唯一标识 | 无 | 一个配置文件，如 `order-service.yaml` |
| **ServiceName** | 服务名 | 无 | 一个微服务，如 `order-service` |
| **Cluster** | 服务下的集群 | `DEFAULT` | 同城多机房、读写分离 |
| **Instance** | 服务实例 | 无 | 一个 IP:Port |

**隔离层级建议**：

```
Namespace = 环境（prod / test / dev）         ← 物理隔离，互不可见
   └── Group = 业务线 / 子系统（PAY_GROUP…）   ← 逻辑分组
        └── DataId / Service = 具体资源
```

> ⚠️ 注意：Namespace 用 **ID**（UUID 或自定义 ID）而非名称来引用。代码里配的是 namespace **id**，控制台显示的是名称。

---

## 3. 架构与一致性协议

Nacos 在不同场景对一致性要求不同，因此**同时支持两类一致性协议**：

| 协议 | 模式 | 用于 | 特点 |
|------|------|------|------|
| **Distro** | AP（最终一致） | 临时实例（service discovery） | 高可用，节点各自处理写入后异步同步，适合大量临时实例心跳 |
| **Raft / JRaft** | CP（强一致） | 持久化实例、配置数据 | 强一致，Leader 选举，适合配置等不容数据丢失场景 |

关键点：

- **临时实例（ephemeral=true，默认）** 走 Distro，靠心跳保活，宕机自动摘除——Spring Cloud 默认就是临时实例。
- **持久化实例（ephemeral=false）** 走 Raft，需手动注销，适合数据库/中间件等基础设施登记。
- **2.x 起引入 gRPC 长连接**，替代 1.x 的 HTTP 短轮询，性能与实时性大幅提升（这也是 9848/9849 端口的来源）。
- 配置数据默认持久化到 **MySQL**（生产必备；Derby 仅用于单机试用）。

架构分层（自底向上）：存储层 → 一致性协议层（Distro/Raft）→ 核心能力层（Naming/Config）→ 接入层（OpenAPI / SDK / Console）。

---

## 4. 端口速查

| 端口 | 用途 | 说明 |
|------|------|------|
| **8848** | 主服务端口 / OpenAPI / Admin API | 客户端 HTTP 接入、`/nacos/v3/admin/...` |
| **8080** | 控制台 Console API | 3.x 控制台独立端口 `/v3/console/...` |
| **9848** | 客户端 gRPC | 2.x+ 客户端长连接（= 主端口 + 1000） |
| **9849** | 服务端 gRPC | 集群节点间通信（= 主端口 + 1001） |
| **7848** | Raft 端口（旧） | 集群一致性（部分版本/历史） |
| **9080** | MCP Registry API | 需 `nacos.ai.mcp.registry.enabled=true` 开启 |

> K8s 部署时这些端口都要在 Service 中暴露；客户端用 gRPC 时必须放通 **9848**，否则会回退或连接失败。偏移量固定：gRPC 端口 = 主端口（默认 8848）+ 1000 / +1001。

---

## 5. 服务发现与注册

### 5.1 概念回顾

- **注册**：实例启动时把自己（IP、端口、元数据、权重）上报给 Nacos。
- **发现**：消费者从 Nacos 拉取某服务的健康实例列表。
- **健康检查**：临时实例靠**客户端心跳**（gRPC 长连接保活）；持久实例靠**服务端探测**。
- **负载均衡**：客户端侧（如 Spring Cloud LoadBalancer / Ribbon）按权重选实例。

### 5.2 实例的关键属性

| 属性 | 说明 |
|------|------|
| `ephemeral` | 是否临时实例，默认 `true`。临时=心跳保活，持久=服务端探测 |
| `weight` | 权重（默认 1.0），用于流量分配，灰度时可调小 |
| `enabled` | 是否可被发现，下线维护可置 false |
| `healthy` | 健康状态（由心跳/探测决定） |
| `metadata` | 自定义元数据（版本、区域、灰度标签等） |
| `clusterName` | 所属集群，默认 `DEFAULT` |

### 5.3 服务发现最佳实践

- 灰度发布：给新版本实例打 `metadata.version=2.0`，配合网关/负载策略按标路由。
- 优雅下线：先调接口把实例 `enabled=false` 或权重调 0，等存量请求结束再停服。
- 多机房：用 `clusterName` 区分，配 `same-cluster-preferred` 优先同机房调用。

---

## 6. 配置中心

### 6.1 配置定位

一条配置 = `namespace + group + dataId`。`dataId` 命名约定（Spring Cloud）：

```
${prefix}-${spring.profiles.active}.${file-extension}
例： order-service-prod.yaml
```

- `prefix` 默认取 `spring.application.name`。
- `file-extension` 支持 `properties` / `yaml` / `json` 等，需与内容格式一致。

### 6.2 配置的动态刷新

- 客户端通过 gRPC 长连接订阅配置变更，**秒级推送**。
- Spring Cloud 中给 Bean 加 `@RefreshScope`，配置变更后自动刷新该 Bean 的属性。
- `@ConfigurationProperties` 绑定的属性默认支持刷新，无需 `@RefreshScope`。

### 6.3 多配置与共享配置

一个应用可加载多个配置文件，优先级从高到低：

1. `${dataId}-${profile}.${ext}`（带 profile）
2. `${dataId}.${ext}`（不带 profile）
3. `extension-configs[n]`（扩展配置，n 越大优先级越高）
4. `shared-configs[n]`（共享配置，多个应用共用，如公共数据库连接池参数）

### 6.4 配置灰度（Beta 发布）

Nacos 支持配置灰度：先对指定 IP 列表推送新配置验证，确认后再全量发布，降低改配置炸全站的风险。

---

## 7. Spring Cloud / Spring Boot 集成

> 这是你们栈的主路径。使用 **Spring Cloud Alibaba**，注意版本对齐（Spring Boot ↔ Spring Cloud ↔ Spring Cloud Alibaba 三者版本要匹配，否则启动报错）。

### 7.1 依赖

```xml
<!-- 服务发现 -->
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<!-- 配置中心 -->
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<!-- 让 bootstrap 配置生效（Spring Boot 2.4+ 需显式引入） -->
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

### 7.2 配置（推荐放 `bootstrap.yml`，配置中心需在应用上下文前加载）

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: nacos.infra.svc:8848
        namespace: ${NACOS_NS:prod}        # 命名空间 ID
        group: PAY_GROUP
        metadata:
          version: 1.0.0
        # username/password 或 ak/sk 走鉴权
      config:
        server-addr: nacos.infra.svc:8848
        namespace: ${NACOS_NS:prod}
        group: PAY_GROUP
        file-extension: yaml
        refresh-enabled: true
        # 共享配置（多服务公用）
        shared-configs:
          - data-id: common-db.yaml
            group: COMMON_GROUP
            refresh: true
        # 扩展配置
        extension-configs:
          - data-id: order-service-ext.yaml
            group: PAY_GROUP
            refresh: true
```

### 7.3 动态刷新示例

```java
@RestController
@RefreshScope                         // 配置变更后该 Bean 重新注入
public class FeatureController {

    @Value("${feature.discount.enabled:false}")
    private boolean discountEnabled;  // Nacos 改值后自动更新

    @GetMapping("/flag")
    public boolean flag() { return discountEnabled; }
}
```

### 7.4 服务调用（OpenFeign + LoadBalancer）

```java
@FeignClient(name = "inventory-service")   // 直接用 Nacos 服务名
public interface InventoryClient {
    @GetMapping("/api/stock/{sku}")
    Stock get(@PathVariable String sku);
}
```

> Spring Cloud 2020+ 已移除 Ribbon，负载均衡用 `spring-cloud-loadbalancer`（starter-discovery 通常已带）。

---

## 8. 常用 OpenAPI 速查

> Nacos 3.x 把 API 重构为 **Admin API**（`/nacos/v3/admin/...`，端口 8848）与 **Console API**（`/v3/console/...`，端口 8080）。1.x 的 `/nacos/v1/...` 与 2.x 的 `/nacos/v2/...` 仍保留兼容，老脚本可继续用。以下给出常用兼容版（v1/v2）+ v3 admin 示例。

### 8.1 配置中心 API

```bash
# 获取配置（v1 兼容，最常用）
curl -X GET "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=order-service.yaml&group=PAY_GROUP&tenant=<namespaceId>"

# 发布/更新配置
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "dataId=order-service.yaml" \
  -d "group=PAY_GROUP" \
  -d "tenant=<namespaceId>" \
  -d "type=yaml" \
  --data-urlencode "content=feature:
  discount:
    enabled: true"

# 删除配置
curl -X DELETE "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=order-service.yaml&group=PAY_GROUP&tenant=<namespaceId>"

# 监听配置变更（长轮询，Content-MD5 变化即返回）
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs/listener" \
  -H "Long-Pulling-Timeout: 30000" \
  --data-urlencode "Listening-Configs=order-service.yaml^2group=PAY_GROUP^2contentMD5=<md5>^2tenant=<ns>^1"
```

### 8.2 服务发现 API

```bash
# 注册实例
curl -X POST "http://127.0.0.1:8848/nacos/v1/ns/instance" \
  -d "serviceName=order-service" -d "ip=10.0.0.5" -d "port=8080" \
  -d "namespaceId=<ns>" -d "groupName=PAY_GROUP" \
  -d "ephemeral=true" -d "weight=1.0"

# 注销实例
curl -X DELETE "http://127.0.0.1:8848/nacos/v1/ns/instance?serviceName=order-service&ip=10.0.0.5&port=8080&namespaceId=<ns>"

# 查询健康实例列表（消费者发现用）
curl -X GET "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=order-service&namespaceId=<ns>&groupName=PAY_GROUP&healthyOnly=true"

# 发送心跳（临时实例保活，SDK 自动做）
curl -X PUT "http://127.0.0.1:8848/nacos/v1/ns/instance/beat?serviceName=order-service&ip=10.0.0.5&port=8080"

# v3 Admin API 形式（3.x）
curl -X GET "http://127.0.0.1:8848/nacos/v3/admin/ns/instance/list?serviceName=order-service&namespaceId=<ns>"
```

### 8.3 元数据 / 健康

```bash
# 更新实例权重或上下线（灰度/优雅停机）
curl -X PUT "http://127.0.0.1:8848/nacos/v1/ns/instance" \
  -d "serviceName=order-service" -d "ip=10.0.0.5" -d "port=8080" \
  -d "weight=0.1" -d "enabled=true" -d "metadata={\"version\":\"2.0\"}"
```

---

## 9. 鉴权与多环境隔离

### 9.1 开启鉴权

服务端 `application.properties`：

```properties
nacos.core.auth.enabled=true
nacos.core.auth.server.identity.key=<自定义>
nacos.core.auth.server.identity.value=<自定义>
nacos.core.auth.plugin.nacos.token.secret.key=<Base64编码的密钥>
```

### 9.2 登录拿 token（OpenAPI 调用前）

```bash
curl -X POST "http://127.0.0.1:8848/nacos/v1/auth/login" \
  -d "username=nacos" -d "password=<password>"
# 返回 {"accessToken":"xxx","tokenTtl":18000,...}
# 后续请求带上 ?accessToken=xxx 或 Header
```

### 9.3 环境隔离策略

| 方式 | 隔离强度 | 适用 |
|------|----------|------|
| 多 **Namespace** | 强（互不可见） | 环境隔离 prod/test/dev、多租户 |
| 多 **Group** | 中（逻辑） | 同环境内不同业务线 |
| 多 **集群部署** | 最强（物理） | 安全等级高的金融/核心系统 |

> 生产强烈建议：**prod 独立 namespace + 独立账号 + 最小权限**，避免测试误操作影响生产配置。

---

## 10. AI 管理中心（MCP Registry）

Nacos 3.x 把自身扩展为 AI 能力注册中枢，可把企业内部 API/工具统一注册供 AI（产品、Claude、Codex 等）发现调用。

### 10.1 开启

```properties
nacos.ai.mcp.registry.enabled=true     # 默认关闭，需显式开启
nacos.ai.mcp.registry.port=9080        # MCP Registry API 端口
```

### 10.2 核心能力

| 能力 | 说明 |
|------|------|
| MCP Server 动态注册 | 增删改查 MCP 服务，多 namespace 隔离，版本控制 |
| 描述/参数热更新 | 工具描述、参数定义运行时更新，无需重启 |
| 工具动态开关 | 运行时启停某工具，可一键熔断高危工具 |
| 零改造升级 | 把现有 HTTP/Dubbo 接口转换为 MCP 协议接口 |
| 全栈打通 | 注册信息自动同步到配置中心与服务发现 |

### 10.3 配套：Nacos MCP Router

标准 MCP Server，提供 MCP 的智能检索 / 安装 / 代理。两种模式：

- **Router 模式**（默认）：推荐、分发、安装、代理其他 MCP Server。
- **Proxy 模式**（`MODE=proxy`）：把 stdio / SSE 协议的 MCP Server 一键转换为 Streamable HTTP，便于私有化部署与统一暴露。

> 用法详见配套《基于 Nacos 的企业级 AI 注册中心》架构方案。日常开发只需记住：内部接口要给 AI 用 → 在 Nacos 注册为 MCP 工具 → AI 客户端经网关/Router 发现。

---

## 11. SDK 速查

### 11.1 Java SDK（`nacos-client`）

```java
// 服务发现
NamingService naming = NamingFactory.createNamingService(props);
naming.registerInstance("order-service", "10.0.0.5", 8080);     // 注册
List<Instance> list = naming.selectInstances("order-service", true); // 健康实例
naming.subscribe("order-service", event -> { /* 实例变更回调 */ });

// 配置
ConfigService config = ConfigFactory.createConfigService(props);
String content = config.getConfig("order-service.yaml", "PAY_GROUP", 5000);
config.addListener("order-service.yaml", "PAY_GROUP", new Listener() {
    public void receiveConfigInfo(String cfg) { /* 配置变更回调 */ }
    public Executor getExecutor() { return null; }
});
config.publishConfig("order-service.yaml", "PAY_GROUP", "key: value", "yaml");
```

`props` 常用项：`serverAddr`、`namespace`、`username`/`password`、`accessKey`/`secretKey`。

### 11.2 其他语言

- **Go**：`nacos-sdk-go`
- **Python**：`nacos-sdk-python`
- **Node.js**：`nacos`（npm）
- **C++/C#**：官方/社区 SDK
- **Spring Cloud Alibaba**：对 Java SDK 的封装，最省心

---

## 12. 运维手册

### 12.1 Docker 单机快速启动（开发/试用）

```bash
docker run -d --name nacos \
  -e MODE=standalone \
  -p 8848:8848 -p 9848:9848 -p 8080:8080 \
  nacos/nacos-server:v3.0.1
# 控制台： http://127.0.0.1:8080  （账号 nacos / nacos，首次需改密码）
```

### 12.2 集群部署要点（生产）

- **节点数 ≥ 3**（奇数），满足 Raft 选举多数派。
- **外置 MySQL**，所有节点共用，配 `SPRING_DATASOURCE_PLATFORM=mysql` + 连接串。
- 配置 `cluster.conf` 列出所有节点 `ip:8848`。
- 前置负载均衡（VIP / SLB / K8s Service）暴露给客户端。

### 12.3 K8s 部署（StatefulSet 关键片段）

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: nacos, namespace: infra }
spec:
  replicas: 3
  serviceName: nacos-headless
  template:
    spec:
      containers:
      - name: nacos
        image: nacos/nacos-server:v3.0.1
        env:
        - { name: MODE,                         value: cluster }
        - { name: SPRING_DATASOURCE_PLATFORM,   value: mysql }
        - { name: MYSQL_SERVICE_HOST,           value: mysql.infra.svc }
        - { name: NACOS_AI_MCP_REGISTRY_ENABLED, value: "true" }  # 需要 AI 能力时
        ports:
        - { containerPort: 8848 }   # 主/OpenAPI
        - { containerPort: 9848 }   # 客户端 gRPC
        - { containerPort: 9849 }   # 服务端 gRPC
        - { containerPort: 8080 }   # 控制台
        - { containerPort: 9080 }   # MCP Registry（开启时）
        readinessProbe:
          httpGet: { path: /nacos/v3/admin/core/state, port: 8848 }
```

### 12.4 监控与备份

- **监控**：Nacos 暴露 Prometheus 指标（`/nacos/actuator/prometheus`），接入 Grafana 看连接数、配置推送、Raft 状态。
- **备份**：定期备份 MySQL（配置/持久实例的真相源）；用配置导出功能做版本快照。
- **告警**：关注节点存活、Leader 抖动、长连接数、配置推送失败率。

---

## 13. 常见问题排查

| 现象 | 可能原因 | 排查 / 处理 |
|------|----------|-------------|
| 客户端注册不上 / 报 gRPC 连接失败 | 9848 端口未放通 | 防火墙/Service 放通 8848 **和** 9848（gRPC=主端口+1000） |
| 配置读不到 / 启动取不到值 | 放在 `application.yml` 而非 `bootstrap.yml`；缺 bootstrap 依赖 | 配置中心项放 `bootstrap.yml`，引入 `spring-cloud-starter-bootstrap` |
| namespace 不生效，总读 public | 配了 namespace **名称** 而非 **ID** | 改成控制台里的 namespace ID |
| `@Value` 配置不刷新 | Bean 缺 `@RefreshScope` | 加注解，或改用 `@ConfigurationProperties` |
| 启动报版本不兼容 | Spring Boot/Cloud/Alibaba 版本错配 | 按官方版本对照表对齐三者版本 |
| 实例不健康/被频繁摘除 | 心跳超时、网络抖动、GC 停顿 | 查长连接、调健康检查阈值、排查实例负载 |
| 配置中文乱码 | OpenAPI 未 URL 编码 | content 用 `--data-urlencode`，注意编码 |
| 集群脑裂 / Leader 频繁切换 | 节点数为偶数、网络分区 | 用奇数节点、检查节点间 9849 互通与时延 |
| 控制台打不开 | 3.x 控制台在 8080，不在 8848 | 访问 `http://host:8080` |
| 配置改了线上没生效 | `refresh: false` 或目标 Bean 未刷新 | 检查 `refresh-enabled` 与 `@RefreshScope` |

---

## 14. 最佳实践清单

- ✅ **环境隔离用 Namespace**，业务分组用 Group，prod 独立 namespace + 独立账号。
- ✅ **配置中心项放 `bootstrap.yml`**，并引入 bootstrap starter。
- ✅ **生产开启鉴权**，密钥走环境变量/密管，不进 Git。
- ✅ **生产用 MySQL + ≥3 节点集群**，Derby/单机仅试用。
- ✅ **gRPC 端口（9848/9849）务必放通**，K8s Service 全端口暴露。
- ✅ **改配置先灰度（Beta）**，验证后再全量。
- ✅ **优雅上下线**：调权重/`enabled` 而非直接 kill。
- ✅ **共享配置抽到 `shared-configs`**，避免每个服务重复维护公共参数。
- ✅ **版本对齐**：Spring Boot ↔ Cloud ↔ Cloud Alibaba 严格按对照表。
- ✅ **监控 + 备份**：接 Prometheus，定期备份 MySQL。
- ✅ **AI 能力**：要给 Claude/Codex 用的内部接口，注册为 MCP 工具，经网关统一鉴权，高危工具默认下线。

---

## 15. 参考资料

校订于 2026-06，建议以官方最新文档为准：

- [Nacos 概览（官方）](https://nacos.io/docs/latest/overview/)
- [Nacos 架构](https://nacos.io/en/docs/latest/architecture/)
- [Admin API（v3.0）](https://nacos.io/en/docs/v3.0/manual/admin/admin-api/)
- [Console API（v3.0）](https://nacos.io/en/docs/v3.0/manual/admin/console-api/)
- [客户端 OpenAPI（v3.1）](https://nacos.io/en/docs/v3.1/manual/user/open-api/)
- [MCP Server 自动注册与发现手册](https://nacos.io/en/docs/latest/manual/user/ai/mcp-auto-register/)
- [Nacos 3.0 正式发布](https://nacos.io/en/blog/nacos-gvr7dx_awbbpb_gg16sv97bgirkixe/)
- [Nacos 2.0 架构与 gRPC 长连接解析](https://www.alibabacloud.com/blog/an-in-depth-insight-into-nacos-2-0-architecture-and-new-model-with-a-supported-grpc-persistent-connection_597804)
