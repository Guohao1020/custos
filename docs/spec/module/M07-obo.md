---
id: M07
title: OBO 委托（STS / token-exchange）
status: done
sprint: v0.2
progress: 100
manualProgress: false
desc: "Authenticator SPI + JwtAuthenticator · AgentScopeResolver SPI · Sts/DefaultSts：用户∩Agent∩请求 最小交集委托令牌（sub=用户/act=Agent）。identity 10 单测全绿。"
docs:
  - { title: "OBO 设计 spec", path: "docs/superpowers/specs/2026-06-09-custos-obo-design.md" }
  - { title: "OBO 实现计划", path: "docs/superpowers/plans/2026-06-09-custos-obo.md" }
subtasks:
  - id: M07-S1
    title: "实现主体认证与代表用户的委托签发"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/Principal.java"
      - "identity/src/main/java/io/custos/identity/Authenticator.java"
      - "identity/src/main/java/io/custos/identity/JwtAuthenticator.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-obo-design.md#3. 组件与接口契约"
      - "docs/superpowers/plans/2026-06-09-custos-obo.md#Task 1: Principal + Authenticator + JwtAuthenticator + TokenService.issueOnBehalf"
  - id: M07-S2
    title: "令牌交换按双方权限交集收窄作用域"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/AgentScopeResolver.java"
      - "identity/src/main/java/io/custos/identity/InMemoryAgentScopeResolver.java"
      - "identity/src/main/java/io/custos/identity/Sts.java"
    docs:
      - "docs/superpowers/specs/2026-06-09-custos-obo-design.md#4. 交集语义"
      - "docs/superpowers/plans/2026-06-09-custos-obo.md#Task 2: AgentScopeResolver + Sts + DefaultSts（交集编排）"
---

# M07 · OBO 委托

竞品均无的能力：Agent 代表用户行动，权限取「用户授予 ∩ Agent 允许 ∩ 本次请求」最小交集，act 入审计可追溯。
