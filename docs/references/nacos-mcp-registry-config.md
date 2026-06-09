# Nacos：MCP Registry 与 配置热更新

- **标题**：Nacos 3.x 的 MCP/AI Registry 与 配置中心热更新（秒级推送）
- **来源 URL**：
  - 概览/架构：https://nacos.io/docs/latest/overview/ · https://nacos.io/docs/latest/architecture/
  - MCP 自动注册：https://nacos.io/docs/latest/manual/user/ai/mcp-auto-register/
  - Admin/Console API：https://nacos.io/docs/v3.0/manual/admin/admin-api/
  - 仓库（Apache-2.0，国产）：https://github.com/alibaba/nacos
- **校订**：2026-06

## 核心要点（中文摘要）

- **三元组定位**：`Namespace + Group + (DataId|ServiceName)`；Namespace（用 **ID** 引用）= 最强隔离，Group = 逻辑分组。
- **双一致性**：服务发现临时实例走 **Distro（AP）**；**配置数据 + 持久实例走 Raft/JRaft（CP 强一致）**——配置不丢不脏。
- **配置热更新**：2.x+ 客户端经 **gRPC 长连接（端口 9848）秒级推送**；Spring 侧 `@RefreshScope`/`@ConfigurationProperties` 自动刷新。**配置灰度（Beta）**先按 IP 推送验证再全量。
- **MCP/AI Registry（3.x）**：`nacos.ai.mcp.registry.enabled=true`（端口 9080）；MCP Server 动态注册（多 ns、版本）、工具描述/参数热更新、**工具动态开关（一键熔断高危工具）**、零改造升级（HTTP/Dubbo→MCP）。源码含 `ai/`、`ai-registry-adaptor/`，并扩展 **A2A（Agent2Agent）/ agentspecs / skills**。配套 **Nacos MCP Router**（router/proxy 模式）。
- **端口**：8848（主/OpenAPI/Admin）、8080（Console）、9848（客户端 gRPC，必需）、9849（服务端 gRPC）、9080（MCP Registry）。
- **鉴权**：`nacos.core.auth` + token；生产建议独立 namespace+账号+最小权限。

## 对 Custos 的影响（护城河）

- 策略存 Nacos 配置（Raft CP）+ gRPC 长连接 → **秒级权限变更与吊销**（`05`）。
- Namespace 隔离对齐 `04` 的 domain 多租户；MCP Registry + 工具熔断对齐 `06` 经纪与高危熔断。
- **红线**：密钥/明文凭证绝不进 Nacos；只放策略/资源目录/黑名单等非敏感数据。
