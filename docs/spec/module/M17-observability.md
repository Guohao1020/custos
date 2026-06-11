---
id: M17
title: 可观测性（metrics · 结构化日志 · tracing）
status: done
sprint: v0.6
progress: 100
manualProgress: false
desc: "生产环境出问题可定位：broker 经框架无关的 BrokerMetrics SPI 埋点（决策 allow/deny/require-approval 计数、审批动作、凭证签发/撤销计数、查询/PDP/签发/撤销四个耗时 timer），app 经 Micrometer/Actuator 落地为 Prometheus 文本 + 4 个运行态 gauge（seal 态/活跃租约/资源数/审批队深）；/actuator/prometheus admin-gated（无 token 401），/actuator/health 开放。compose 起 Prometheus（Bearer 抓取）+ Grafana（匿名 Viewer + 预置「Custos 概览」仪表盘）。指标只承载有界 tag 与计数/耗时，绝不含 agent/resource/SQL/token/凭证。tracing 按 YAGNI 暂不做。"
docs:
  - { title: "可观测性设计 spec", path: "docs/superpowers/specs/2026-06-11-observability-design.md" }
  - { title: "可观测性实现计划", path: "docs/superpowers/plans/2026-06-11-observability.md" }
  - { title: "定位/ROADMAP（v0.6 真相源）", path: "docs/ROADMAP.md" }
subtasks:
  - id: M17-S1
    title: "broker BrokerMetrics SPI + PEP 埋点（决策/审批/凭证 counter + 查询/PDP/签发/撤销 timer，NOOP 默认）"
    done: true
    code:
      - broker/src/main/java/io/custos/broker/BrokerMetrics.java
      - broker/src/main/java/io/custos/broker/BrokerService.java
      - broker/src/test/java/io/custos/broker/BrokerMetricsIT.java
    docs:
      - docs/superpowers/specs/2026-06-11-observability-design.md#指标清单
  - id: M17-S2
    title: "app Micrometer/Actuator：admin-gated /actuator/prometheus（无 token 401）+ /actuator/health 开放 + 4 运行态 gauge"
    done: true
    code:
      - app/src/main/java/io/custos/app/metrics/MicrometerBrokerMetrics.java
      - app/src/main/java/io/custos/app/metrics/MetricsConfig.java
    docs:
      - docs/superpowers/specs/2026-06-11-observability-design.md#鉴权与暴露
  - id: M17-S3
    title: "compose 监控栈：Prometheus（Bearer scrape）+ Grafana（匿名 Viewer + provisioning）+「Custos 概览」仪表盘 + demo 段"
    done: true
    code:
      - examples/prometheus/prometheus.yml
      - examples/grafana/dashboards/custos-dashboard.json
      - examples/docker-compose.yml
    docs:
      - docs/superpowers/specs/2026-06-11-observability-design.md#compose 监控栈
---

# M17 · 可观测性（metrics · 结构化日志 · tracing）

生产出事要能定位。本模块补齐 metrics 一层：broker 经框架无关的 `BrokerMetrics` SPI 在 PEP 编排关键点埋点，
app 以 Micrometer/Actuator 落地为 Prometheus 文本 + 运行态 gauge，compose 起 Prometheus + Grafana 把指标变成图。
作为 M16 console 实时监控之外、面向 Prometheus/Grafana 生态的标准可观测出口。

## 已交付

- **指标体系**：4 counter（`custos_decisions_total{decision}`、`custos_approvals_total{action}`、`custos_credentials_issued_total`、`custos_credentials_revoked_total`）+ 4 timer（query/pdp/issue/revoke duration）+ 4 gauge（`custos_seal_sealed`、`custos_leases_active`、`custos_resources_count`、`custos_approvals_pending`）。
- **鉴权与暴露**：`management.endpoints.web.exposure.include=health,prometheus`；`AdminTokenFilter` 只门控 `/actuator/prometheus`（无 token → 401），放行 `/actuator/health`（200 `{"status":"UP"}`）。
- **compose 监控栈**：`prometheus`（Bearer = `CUSTOS_ADMIN_TOKEN` 抓取 `custos:8080`）+ `grafana`（匿名 Viewer，provisioning 自动挂数据源 + 「Custos 概览」仪表盘，宿主 3001）。

## 脱敏铁律

tag 仅用有界枚举（decision/action），**绝不**用 agent / resource 名 / SQL / token 作 label；指标值只是计数/耗时，从不含凭证。

## 不做（YAGNI）

分布式 tracing、结构化日志改造、告警规则、高基数 label、把指标推 Nacos、console 内嵌 Grafana —— 本轮均不做，留待后续按需。
