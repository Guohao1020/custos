# MCP 规范 与 SEP-835（工具级 scope）

- **标题**：Model Context Protocol（MCP）规范 与 SEP-835（工具/动作级授权 scope）
- **来源 URL**：
  - MCP 规范：https://modelcontextprotocol.io/ · https://spec.modelcontextprotocol.io/
  - MCP 提案（SEP）流程与编号：https://github.com/modelcontextprotocol/specification （SEP-835 为工具级授权相关提案）
- **校订**：2026-06（标准演进中，落地以最新规范为准）

## 核心要点（中文摘要）

- **MCP 是什么**：标准化 LLM/Agent 与外部工具、数据源、能力之间的接口协议。以 **server 暴露 tools / resources / prompts**，client（Claude/Codex 等）发现并调用。传输支持 stdio、SSE、Streamable HTTP。
- **工具调用模型**：每个 tool 有名字、输入 schema（JSON Schema）、描述；client 按 schema 组织调用，server 执行并回结果。
- **SEP-835 / 工具级 scope 的意义**：把授权粒度下沉到**单个工具 / 动作**级别——不是"能不能连这个 server"，而是"这个主体此刻能不能调 `query_orders` 这个工具、做 `read` 这个动作"。这与传统粗粒度 API 授权不同，契合 Agent 场景"按工具/动作最小授权"。

## 对 Custos 的影响

- Custos 经纪层（`06`）以 **MCP server** 形态暴露受治理工具（IF1）；每次工具调用转成策略层（`04`）的一个 Decision Request。
- **工具/动作级 scope** 映射为 PDP 的 `obj=tool:<server>/<tool>` + `act=read|write|...`，用 jCasbin `keyMatch` 通配承载（`04` §3）。
- OBO 作用域令牌（`03`）携带 `scope`，与工具级授权对齐：用户∩Agent 交集 → 再按策略收窄到具体工具/动作。
- 标准仍演进 → Custos 接口抽象、保持可适配（PRD 风险表）。
