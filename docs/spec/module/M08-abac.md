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
  - id: M08-S1
    title: "决策模型升级多租户域并全量回归"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/DecisionRequest.java"
      - "authz/src/main/java/io/custos/authz/Decision.java"
      - "authz/src/main/java/io/custos/authz/CasbinPdp.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-abac.md:35-258"
      - "docs/superpowers/specs/2026-06-09-custos-abac-design.md#4. RBAC domain 模型"
  - id: M08-S2
    title: "实现属性策略与请求风险评分扩展点"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/AbacPolicy.java"
      - "authz/src/main/java/io/custos/authz/RiskScorer.java"
      - "authz/src/main/java/io/custos/authz/DefaultRiskScorer.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-abac.md:259-380"
      - "docs/superpowers/specs/2026-06-09-custos-abac-design.md:93-102"
  - id: M08-S3
    title: "支持允许拒绝与需审批的三态决策链"
    done: true
    code:
      - "authz/src/main/java/io/custos/authz/ApprovalHook.java"
      - "authz/src/main/java/io/custos/authz/DenyApprovalHook.java"
      - "authz/src/main/java/io/custos/authz/AbacPdp.java"
    docs:
      - "docs/superpowers/plans/2026-06-09-custos-abac.md:382-562"
      - "docs/superpowers/specs/2026-06-09-custos-abac-design.md#2. 架构与数据流（装饰链）"
---

# M08 · ABAC / 风险分级

在可解释 RBAC PDP 上叠加：domain 多租户隔离 + 属性/风险/上下文细判 + 高危 JIT 审批钩子；
策略走 PBAC（Nacos 声明式/版本/秒级热更）。
