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
  - id: M03-S1
    title: "建立身份模块与 Agent 标识命名"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/AgentId.java"
      - "identity/src/test/java/io/custos/identity/AgentIdTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-identity-jwt.md:33-162"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:106-116"
  - id: M03-S2
    title: "签发并校验 ES256 的 JWT 会话令牌"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/JwtTokenService.java"
      - "identity/src/test/java/io/custos/identity/JwtTokenServiceTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-identity-jwt.md:165-228"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:109-116"
  - id: M03-S3
    title: "已吊销令牌即时拒绝的黑名单机制"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/InMemoryBlacklist.java"
      - "identity/src/test/java/io/custos/identity/RevocationTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-identity-jwt.md:405-447"
      - "docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md:114-115"
---

# M03 · 身份层 JWT

per-session 作用域令牌，sub=SPIFFE-id、scope/aud/exp/act，黑名单可热更新（计划 4 接 Nacos）。
