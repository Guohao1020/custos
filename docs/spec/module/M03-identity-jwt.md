---
id: M03
title: 身份层（per-session JWT · ES256）
status: done
sprint: v0.1
progress: 100
manualProgress: false
desc: "SPIFFE 风格 AgentId · per-session JWT(ES256) 签发/校验 · 黑名单吊销。6 单测全绿。"
docs:
  - { title: "身份层设计", path: "docs/design/03-identity-design.md" }
  - { title: "实现计划 3/5 · 身份 JWT", path: "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-identity-jwt.md" }
subtasks:
  - { title: "P3-T1 identity 模块脚手架 + AgentId", done: true }
  - { title: "P3-T2 TokenService 签发/校验 JWT ES256", done: true }
  - { title: "P3-T3 吊销黑名单（valid-but-revoked 拒）", done: true }
---

# M03 · 身份层 JWT

per-session 作用域令牌，sub=SPIFFE-id、scope/aud/exp/act，黑名单可热更新（计划 4 接 Nacos）。
