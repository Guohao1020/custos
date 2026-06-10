---
id: M13
title: SDK（spring-boot-starter）+ CLI 完善
status: done
sprint: v0.3
progress: 100
manualProgress: false
desc: "custos-spring-boot-starter（Properties+AutoConfiguration+CustosClient，backoff 语义）+ CLI 补 query/operator seal。sdk 5 单测 + cli 2 单测全绿，8 模块 verify 通过。"
docs:
  - { title: "SDK 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-sdk-design.md" }
  - { title: "SDK 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-sdk.md" }
  - { title: "仓库脚手架设计", path: "docs/design/08-repo-scaffold.md" }
subtasks:
  - id: M13-S1
    title: "提供 Spring Boot 自动装配的客户端 SDK"
    done: true
    code:
      - "sdk/src/main/java/io/custos/sdk/CustosClientProperties.java"
      - "sdk/src/main/java/io/custos/sdk/CustosClient.java"
      - "sdk/src/main/java/io/custos/sdk/CustosClientAutoConfiguration.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-sdk.md#Task 1"
      - "docs/superpowers/specs/2026-06-10-custos-sdk-design.md#3. 接口契约"
  - id: M13-S2
    title: "用假宿主校验客户端请求形状"
    done: true
    code:
      - "sdk/src/test/java/io/custos/sdk/CustosClientTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-sdk.md#Task 2"
      - "docs/superpowers/specs/2026-06-10-custos-sdk-design.md#5. 测试策略（纯单元，无 Docker）"
  - id: M13-S3
    title: "CLI 补齐查询与重新密封子命令"
    done: true
    code:
      - "cli/src/main/java/io/custos/cli/CustosCli.java"
      - "cli/src/test/java/io/custos/cli/CustosCliHttpTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-sdk.md#Task 3"
---

# M13 · SDK + CLI 完善

业务服务"一行依赖拿到 client"；CLI 对齐 host 全部端点。注解语法糖/重试熔断留后续。
