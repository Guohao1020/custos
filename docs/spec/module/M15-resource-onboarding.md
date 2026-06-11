---
id: M15
title: 资源接入（资源注册表 · 高权限密钥 Barrier 托管 · 多 DB 方言）
status: done
sprint: v0.5
progress: 100
manualProgress: false
desc: "运行时注册数据库资源 + 配高权限管理凭证（整条经 Barrier 加密落盘、用后 Zeroize、可 rotate-admin 轮换），custos 据此现场签发即用即焚只读凭证、全程 secretless。泛型资源分类（db.relational/db.kv/mq/llm 留位）+ 两种引擎形状（动态凭证型已实现/静态密钥型待 v0.6）。hybrid 适配器：MySQL/PostgreSQL 内置 + SQL 模板逃生口。去硬编码 target-jdbc-url，broker 按 resource/role 解析。engine resource 包 9 测试类全绿 + broker/app 回归。"
docs:
  - { title: "资源接入设计 spec", path: "docs/superpowers/specs/2026-06-11-resource-onboarding-design.md" }
  - { title: "资源接入实现计划", path: "docs/superpowers/plans/2026-06-11-resource-onboarding.md" }
  - { title: "定位/ROADMAP（v0.5 真相源）", path: "docs/ROADMAP.md" }
  - { title: "审计准备包 AUDIT-PREP（G7）", path: "docs/audit/AUDIT-PREP.md" }
subtasks:
  - id: M15-S1
    title: "定义泛型资源记录与具名角色数据模型"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/ResourceRecord.java
      - engine/src/main/java/io/custos/engine/resource/RoleDef.java
    docs:
      - docs/superpowers/specs/2026-06-11-resource-onboarding-design.md#§2.0
  - id: M15-S2
    title: "资源记录经 Barrier 加密持久化（落盘无明文密码）"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/ResourceStore.java
      - engine/src/test/java/io/custos/engine/resource/ResourceStoreIT.java
    docs:
      - docs/superpowers/specs/2026-06-11-resource-onboarding-design.md#§3
  - id: M15-S3
    title: "凭证适配 SPI 与 MySQL 内置适配器"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/CredentialAdapter.java
      - engine/src/main/java/io/custos/engine/resource/MySqlAdapter.java
  - id: M15-S4
    title: "PostgreSQL 内置适配器"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/PostgresAdapter.java
  - id: M15-S5
    title: "SQL 模板逃生口适配罕见库"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/TemplateAdapter.java
  - id: M15-S6
    title: "按资源现场签发的动态凭证引擎（用后 Zeroize 高权限密钥）"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/DbDynamicEngine.java
    docs:
      - docs/superpowers/specs/2026-06-11-resource-onboarding-design.md#§3
  - id: M15-S7
    title: "资源生命周期编排：注册/列表/轮换/注销 + 试连校验 + 审计"
    done: true
    code:
      - engine/src/main/java/io/custos/engine/resource/ResourceManager.java
  - id: M15-S8
    title: "经纪层按 resource/role 解析签发"
    done: true
    code:
      - broker/src/main/java/io/custos/broker/BrokerService.java
      - broker/src/main/java/io/custos/broker/QueryIntent.java
  - id: M15-S9
    title: "资源接入 REST 端点 + 解封载入挂载（去硬编码目标库）"
    done: true
    code:
      - app/src/main/java/io/custos/app/resource/ResourceController.java
      - app/src/main/java/io/custos/app/operator/OperatorService.java
    docs:
      - docs/superpowers/specs/2026-06-11-resource-onboarding-design.md#§4
  - id: M15-S10
    title: "CLI 资源子命令（register/list/rm/rotate-admin）"
    done: true
    code:
      - cli/src/main/java/io/custos/cli/CustosCli.java
  - id: M15-S11
    title: "demo 迁移：AC0 资源注册 + AC9 密钥托管铁证"
    done: true
    docs:
      - examples/demo.md
---

# M15 · 资源接入（Resource Onboarding）

企业快速对接内部数据库：运行时经 REST/CLI 注册资源 + 高权限管理凭证（Barrier 加密托管），
custos 现场签发即用即焚只读凭证、secretless。承接定位 spec v0.5 首要项。

**泛型留位**：资源分类 `db.relational`（已实现）/ `db.kv` / `mq` / `llm` 开放；两种引擎形状
（动态凭证型已实现，静态密钥型/LLM 网关待 v0.6+）。**v0.5 实现范围限 `db.relational` 动态型。**

**密钥托管**：高权限管理凭证整条记录经 Barrier 加密落 `custos_storage`，绝不明文、绝不进 Nacos，
仅签发/撤销时内存解出、用后 `Zeroize`、可 `rotate-admin` 轮换（AUDIT-PREP G7：剩余缺口为权限过大）。
