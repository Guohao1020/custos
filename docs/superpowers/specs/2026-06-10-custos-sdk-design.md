# Custos SDK（spring-boot-starter）+ CLI 完善 设计规格（M13）

> **类型**：路线图子项目 **M13 / P-SDK**（v0.3）设计。业务服务接入 SDK + 运维 CLI 补全。
> **校订**：2026-06-10 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：生产架构 spec §3/§4（双轨装配·starter）/§7；`docs/design/08-repo-scaffold.md`。前置：生产基座（host REST 端点已存在）。

---

## 1. 目标与范围

降低接入成本：① **`custos-spring-boot-starter`**（新模块 `sdk`）——业务 Spring 服务零样板拿到 `CustosClient`（查询/状态）；② **CLI 补全**——`query`、`seal` 子命令，对齐 host 已有端点。

- **纳入**：`CustosClientProperties`（`custos.client.*`：base-url、admin-token）、`CustosClient`（JDK HttpClient 封装：queryDb/status/sealStatus）、`CustosClientAutoConfiguration` + `AutoConfiguration.imports`；CLI 加 `query`/`seal`。
- **非目标**：注解式凭证注入（`@CustosSecret` 等语法糖留后续）、starter 内置重试/熔断、CLI 交互式模式、SDK 多语言。

## 2. 架构

```
业务服务（Spring Boot 3.x）
  └─ 依赖 custos-spring-boot-starter
       ├─ CustosClientProperties ← application.yml: custos.client.base-url / admin-token
       ├─ CustosClientAutoConfiguration（@ConditionalOnMissingBean → 注册 CustosClient）
       └─ CustosClient ──HTTP──▶ custos-host（/query_db, /operator/status）
```
- **协议**：复用 host 既有 REST 面（生产基座），不新增服务端端点。
- **secretless**：client 只透传 `userToken` 与查询，结果即行数据——SDK 不接触凭证。

## 3. 接口契约

```java
@ConfigurationProperties(prefix = "custos.client")
public class CustosClientProperties { String baseUrl = "http://127.0.0.1:8080"; String adminToken = ""; }

public final class CustosClient {
    public CustosClient(String baseUrl, String adminToken);
    public String queryDb(String tool, String schema, String sql, String userToken);  // 返回 JSON 文本
    public String operatorStatus();                                                    // admin（带 Bearer）
}

@AutoConfiguration
@EnableConfigurationProperties(CustosClientProperties.class)
public class CustosClientAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    CustosClient custosClient(CustosClientProperties p) { ... }
}
```
注册：`sdk/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

CLI 补全（`CustosCli` 增子命令）：
- `custos query --tool db/query_orders --schema appdb --sql "SELECT 1" --user-token <JWT>` → POST `/query_db`（无需 admin token）。
- `custos operator seal` → POST `/operator/seal`（admin）。

## 4. 错误处理

| 场景 | 处理 |
|---|---|
| host 不可达 | CustosClient 抛 `IllegalStateException`（带原因），不静默 |
| 401（admin token 错） | 透传响应体给调用方/CLI 输出 |
| 409（sealed） | 透传（业务可识别 sealed 状态） |

## 5. 测试策略（纯单元，无 Docker）

- 自动装配：`ApplicationContextRunner` + `AutoConfigurations.of(...)`——默认注册 bean、属性绑定生效、用户自定义 bean 时让位（backoff）。
- `CustosClient`：JDK 内置 `com.sun.net.httpserver.HttpServer` 起本地 fake（断言路径/头/体，回放 JSON）。
- CLI 新子命令：同 fake server 验证请求形状。

## 6. YAGNI

不做注解注入语法糖、不内置 Resilience4j、不发布 BOM、不做 Gradle 插件；starter 只解决"一行依赖拿到 client"。
