---
id: M19
title: AI 生态集成（Skill 兑现 · Higress 网关）
status: planned
sprint: v0.6
progress: 0
manualProgress: false
desc: "接入 Nacos AI 生态：Skill required-permission 兑现（Skill 注册表元数据声明调用所需密钥/权限，custos 在激活时按策略兑现）+ Higress 零代码 API→MCP（custos REST admin 经 Higress 网关变成 MCP 工具，Agent 经网关调用，custos 仍做 PEP+审计）。让 custos 成为阿里 AI 参考栈（Spring AI Alibaba + Higress + Nacos）里的安全执行层。"
docs:
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
  - { title: "Nacos AI 中心杠杆点研究", path: "docs/research/nacos-ai-center.md" }
---

# M19 · AI 生态集成（规划 · v0.6）

把 custos 插进 Nacos AI 生态，做被发现的同时做安全执行点。

## 范围（规划）

- Skill required-permission 兑现：Nacos Skill 注册表元数据声明"调用此 skill 所需密钥/权限"，custos 在激活/调用时按 RBAC+ABAC 兑现
- Higress 零代码 API→MCP：custos REST（资源/查询）经 Higress 网关暴露为 MCP 工具；Agent 经网关调用，custos 仍是 PEP + 审计点
- 对齐 Spring AI Alibaba + Higress + Nacos 参考栈卡位

> 动工时走 brainstorm → spec → plan → impl，届时补 subtasks 与锚。LLM 网关（静态密钥型）是独立线，按 ROADMAP 在 v0.6+ 单独规划，不并入本卡。
