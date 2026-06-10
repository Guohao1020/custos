---
id: M08
title: ABAC / 风险分级 PDP
status: done
sprint: v0.2
progress: 100
manualProgress: false
desc: "RBAC 升级带 domain(=Nacos namespace)多租户 · AbacPdp 装饰链(越密级硬约束 + RiskScorer 评分 + 三态分级 + JIT 钩子) · 决策三态 Effect{ALLOW/DENY/REQUIRE_APPROVAL}+risk。authz 15 单测全绿。"
docs:
  - { title: "ABAC 设计 spec", path: "docs/superpowers/specs/2026-06-09-custos-abac-design.md" }
  - { title: "ABAC 实现计划", path: "docs/superpowers/plans/2026-06-09-custos-abac.md" }
  - { title: "策略层设计", path: "docs/design/04-authz-design.md" }
subtasks:
  - { title: "M08-T1 RBAC domain 多租户 + DecisionRequest/Decision 富化(effect+risk) + 回归", done: true }
  - { title: "M08-T2 AbacPolicy + RiskScorer SPI + 确定性 DefaultRiskScorer", done: true }
  - { title: "M08-T3 ApprovalHook + AbacPdp 三态装饰链(越密级/风险分级/JIT)", done: true }
---

# M08 · ABAC / 风险分级

在可解释 RBAC PDP 上叠加：domain 多租户隔离 + 属性/风险/上下文细判 + 高危 JIT 审批钩子；
策略走 PBAC（Nacos 声明式/版本/秒级热更）。
