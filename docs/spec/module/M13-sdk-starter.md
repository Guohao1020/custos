---
id: M13
title: SDK（spring-boot-starter）+ CLI 完善
status: not-started
sprint: v0.3
progress: 0
manualProgress: false
desc: "新模块 sdk=custos-spring-boot-starter（属性绑定+自动装配+CustosClient 打 host REST）；CLI 补 query / operator seal。纯单元（ApplicationContextRunner + JDK HttpServer fake）。spec/plan 已备好。"
docs:
  - { title: "SDK 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-sdk-design.md" }
  - { title: "SDK 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-sdk.md" }
  - { title: "仓库脚手架设计", path: "docs/design/08-repo-scaffold.md" }
subtasks:
  - { title: "M13-T1 sdk 模块：Properties + CustosClient + AutoConfiguration + imports", done: false }
  - { title: "M13-T2 CustosClient 请求形状测试（fake host）", done: false }
  - { title: "M13-T3 CLI 补 query + operator seal 子命令", done: false }
---

# M13 · SDK + CLI 完善

"一行依赖拿到 client"；注解式凭证注入/重试熔断/多语言 SDK 为非目标。
