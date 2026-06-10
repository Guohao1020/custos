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
  - { title: "M13-T1 sdk 模块：Properties + CustosClient + AutoConfiguration + imports", done: true }
  - { title: "M13-T2 CustosClient 请求形状测试（fake host）", done: true }
  - { title: "M13-T3 CLI 补 query + operator seal 子命令", done: true }
---

# M13 · SDK + CLI 完善

业务服务"一行依赖拿到 client"；CLI 对齐 host 全部端点。注解语法糖/重试熔断留后续。
