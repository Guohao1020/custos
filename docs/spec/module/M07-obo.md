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
  - { title: "OBO-T1 Principal + Authenticator + JwtAuthenticator + issueOnBehalf", done: true }
  - { title: "OBO-T2 AgentScopeResolver + Sts + DefaultSts 交集", done: true }
---

# M07 · OBO 委托

竞品均无的能力：Agent 代表用户行动，权限取「用户授予 ∩ Agent 允许 ∩ 本次请求」最小交集，act 入审计可追溯。
