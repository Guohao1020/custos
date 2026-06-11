---
id: M16
title: 后台管理控制台（Admin Console）
status: done
sprint: v0.6
progress: 100
manualProgress: false
desc: "全自建独立 Vue3 + Element Plus 控制台 + 支撑只读端点：防篡改审计链浏览器（按 agent/时间/决策过滤、断链定位）+ 实时监控（seal 态/活跃租约/资源数/决策·拒绝速率）+ 运维动作（逐片解封/密封）+ 资源配置 GUI（注册/轮换/删，高权限密码不回显）+ 审批队列（接 M20）。engine 加审计只读分页查询/计数/决策计数 + 租约 listActive；app 加 /audit·/leases·/monitor/stats·GET /policy 只读端点 + CORS；console 独立工程经 compose 的 nginx 服务部署（同源反代免跨域）。token 仅 sessionStorage、Bearer 拦截、密码不回显。"
docs:
  - { title: "Admin Console 设计 spec", path: "docs/superpowers/specs/2026-06-11-admin-console-design.md" }
  - { title: "Admin Console 实现计划", path: "docs/superpowers/plans/2026-06-11-admin-console.md" }
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "资源接入设计（console 边界 §4.1）", path: "docs/superpowers/specs/2026-06-11-resource-onboarding-design.md" }
depends_on: [M20]
subtasks:
  - id: M16-S1
    title: "engine 审计只读 API：分页查询 / 计数 / 决策计数（脱敏投影）"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/audit/AuditEntry.java
      - engine/src/main/java/io/custos/engine/audit/AuditQuery.java
      - engine/src/main/java/io/custos/engine/audit/HashChainAuditLog.java
    docs:
      - docs/superpowers/specs/2026-06-11-admin-console-design.md#后端只读端点
  - id: M16-S2
    title: "engine 租约只读：LeaseManager.listActive（未撤销/未过期）"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/lease/LeaseManager.java
      - engine/src/main/java/io/custos/engine/lease/DefaultLeaseManager.java
  - id: M16-S3
    title: "app 只读端点：/audit 分页 · /leases · /monitor/stats · GET /policy + 装配接线"
    done: true
    code:
      - app/src/main/java/io/custos/app/monitor/MonitorController.java
      - app/src/main/java/io/custos/app/audit/AuditController.java
    docs:
      - docs/superpowers/specs/2026-06-11-admin-console-design.md#后端只读端点
  - id: M16-S4
    title: "CORS 放行可配 console 源 + Authorization 头（不用通配符）"
    done: true
    code:
      - app/src/main/java/io/custos/app/config/CorsConfig.java
    docs:
      - docs/superpowers/specs/2026-06-11-admin-console-design.md#鉴权与安全
  - id: M16-S5
    title: "console 独立工程：Vue3 + Element Plus 6 视图 + axios Bearer 拦截器 + 登录守卫"
    done: true
    code:
      - console/src/api/client.ts
      - console/src/router/index.ts
    docs:
      - docs/superpowers/specs/2026-06-11-admin-console-design.md#console 面板
  - id: M16-S6
    title: "compose console 服务（nginx 服 dist + /api 反代）+ demo 控制台段"
    done: true
    code:
      - console/Dockerfile
      - examples/docker-compose.yml
    docs:
      - examples/demo.md
---

# M16 · 后台管理控制台（规划 · v0.6 首做）

让安全团队"看得见"系统：审计链能 verify 却不能浏览、运行态零监控——本模块补齐。
console 自建（审计链浏览 + secretless 实时监控 + 运维动作 + 资源 GUI 是 custos 特有，Nacos 控制台展示不了）。

## 范围（规划）

- 防篡改审计链浏览器：按 agent / 时间 / 决策(allow·deny·approve) / 资源过滤；断链定位 seq
- 实时监控：seal 状态、活跃租约、动态账号、决策/拒绝速率、审批队列
- 运维动作：解封、主密钥/资源高权限钥轮换
- 资源配置 GUI：注册资源、填/轮换高权限密钥、看活跃签发（M15 资源接入的图形入口）
- 后端：新增只读 REST（审计分页查询、租约列表、监控统计），admin-gated

> 动工时走 brainstorm → spec → plan → impl，届时本卡补 subtasks 与 code/doc 锚（参照 M15）。
