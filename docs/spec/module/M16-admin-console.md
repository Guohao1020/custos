---
id: M16
title: 后台管理控制台（Admin Console）
status: planned
sprint: v0.6
progress: 0
manualProgress: false
desc: "v0.6 首做。全自建独立后台：防篡改审计链浏览器（按 agent/时间/决策/资源过滤、断链定位）+ 实时监控（seal 态/活跃租约/动态账号/决策·拒绝速率/审批队列）+ 运维动作（解封/轮换）+ 资源配置 GUI（注册资源、填/轮换高权限密钥、看活跃签发）。读 custos REST + Nacos 两边数据呈现统一面板。需新增只读 REST（审计分页查询/租约列表/监控统计）。"
docs:
  - { title: "Admin Console 设计 spec", path: "docs/superpowers/specs/2026-06-11-admin-console-design.md" }
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "资源接入设计（console 边界 §4.1）", path: "docs/superpowers/specs/2026-06-11-resource-onboarding-design.md" }
depends_on: [M20]
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
