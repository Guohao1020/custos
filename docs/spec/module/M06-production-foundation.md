---
id: M06
title: 生产基座（模块化单体 + SPI + custos-host + CLI）
status: done
sprint: Production
progress: 100
manualProgress: false
desc: "SecretsEngine SPI 化 · custos-host（启动即 sealed + REST admin 解封流 + secretless 查询面）· MCP transport · picocli CLI · 端到端宿主 IT。mvn -B verify 7 模块全绿。"
docs:
  - { title: "生产架构 spec", path: "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md" }
  - { title: "实现计划 · 生产基座", path: "docs/superpowers/plans/2026-06-09-custos-production-foundation.md" }
subtasks:
  - id: M06-S1
    title: "抽象密钥引擎 SPI 并与经纪层解耦"
    done: true
    code:
      - "engine/src/main/java/io/custos/engine/secrets/SecretsEngine.java"
      - "engine/src/main/java/io/custos/engine/secrets/SecretsEngineRegistry.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#3. SPI 扩展目录"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:43-193"
  - id: M06-S2
    title: "搭建宿主应用并以密封态引导启动"
    done: true
    code:
      - "app/src/main/java/io/custos/app/config/CustosProperties.java"
      - "app/src/main/java/io/custos/app/engine/EngineBootstrap.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#5. 运行时宿主拓扑 + transport 面 + 解封流"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:196-354"
  - id: M06-S3
    title: "实现运维解封生命周期与受保护管理接口"
    done: true
    code:
      - "app/src/main/java/io/custos/app/operator/OperatorService.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#5. 运行时宿主拓扑 + transport 面 + 解封流"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:357-697"
  - id: M06-S4
    title: "开放策略下发、审计校验与查询入口"
    done: true
    code:
      - "app/src/main/java/io/custos/app/policy/PolicyService.java"
      - "app/src/test/java/io/custos/app/HostEndToEndIT.java:78-81"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#5. 运行时宿主拓扑 + transport 面 + 解封流"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:701-825"
  - id: M06-S5
    title: "提供 MCP stdio 传输与 CLI 客户端"
    done: true
    code:
      - "cli/src/main/java/io/custos/cli/CustosCli.java"
      - "app/src/main/java/io/custos/app/mcp/McpStdioRunner.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#3. SPI 扩展目录"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:828-951"
  - id: M06-S6
    title: "宿主端到端集成测试与容器栈真跑验证"
    done: true
    code:
      - "app/src/test/java/io/custos/app/HostEndToEndIT.java"
      - "app/src/main/resources/application.yml"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-production-architecture-spec.md#9. 测试与验收策略"
      - "docs/superpowers/plans/2026-06-09-custos-production-foundation.md:955-1140"
---

# M06 · 生产基座

把 v0.1 五模块收敛为可端到端运行的生产形态：SPI 可插拔 + 解封后装配运营组件 + REST admin/CLI/MCP 多 transport。
