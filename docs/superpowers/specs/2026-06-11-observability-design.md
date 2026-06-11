---
id: SPEC-M17-OBSERVABILITY
type: spec
title: "M17 可观测性（Micrometer 指标 · Prometheus 端点 · Grafana 预置仪表盘）设计"
status: reviewing
date: 2026-06-11
desc: "给 custos 装上生产级指标:broker 经 BrokerMetrics SPI 埋点(框架无关),app 用 Micrometer+Actuator 暴露 admin-gated /actuator/prometheus,compose 预置 Prometheus+Grafana 含 Custos 仪表盘。保留 M16 的 /monitor/stats 给控制台。"
---

# M17 可观测性（Observability）设计

## 0 · 背景与动机

M16 给了 `GET /monitor/stats`(DB 聚合的 seal 态/审计总数/活跃租约/资源数/决策计数/近窗拒绝率),
适合控制台"一眼看"卡片,但不是生产可观测方案:无时序、无告警、外部 Prometheus/Grafana 抓不到,
也看不到延迟分布(PDP 决策耗时、凭证签发/撤销耗时、查询执行耗时)。M17 装上标准指标栈——
Micrometer 采集 + Actuator 暴露 Prometheus 端点 + compose 预置 Grafana 仪表盘,让运维既能看实时图、
也能接企业既有监控。承接 ROADMAP v0.6「可观测」项与 M16 spec §9（metrics 留 M17)。

## 1 · 已确认的关键决策

| # | 决策 | 取舍 |
|---|---|---|
| D1 | **Micrometer + `spring-boot-starter-actuator`** 暴露 `/actuator/prometheus` | Spring Boot 3.3.2 原生支持、Prometheus 事实标准;`/monitor/stats` 保留给控制台卡片 |
| D2 | **不做 tracing** | v0.6 只做 metrics + 现有哈希链审计;OpenTelemetry span 留后(YAGNI) |
| D3 | **`/actuator/prometheus` admin-gated;`/actuator/health` 开放** | metrics 含拒绝率/请求量/seal 态等运营信息,须门控;health 只回 UP/DOWN 不泄 seal 态,供探针 |
| D4 | **broker 定义 `BrokerMetrics` SPI(NOOP 默认)** | broker 保持框架无关(不依赖 Micrometer/Spring);app 提供 Micrometer 实现经 assemble 注入,沿用 SecretsEngine/ControlPlane 的 SPI 模式 |
| D5 | **compose 加 Prometheus + Grafana(预置 Custos 仪表盘)** | 本地 `docker compose up` 即完全体,起来就能看真图,贴生产姿态 |

## 2 · 架构与组件

```
broker (框架无关)                         app (Spring Boot, Micrometer)            compose
 BrokerMetrics (SPI)                       MicrometerBrokerMetrics                  prometheus
  recordDecision(decision)                  (impl,注入 MeterRegistry)               (scrape custos:8080
  recordQueryDuration(Duration)            MetricsConfig                            /actuator/prometheus
  recordCredIssueDuration/RevokeDuration    ├ 注册 MicrometerBrokerMetrics bean      bearer_token=admin)
  recordCredentialIssued/Revoked            ├ 注册 gauge:seal/leases/resources/      grafana
  recordApproval(action)                    │   approvals-pending(读 OperatorService) (provisioned datasource
  NOOP (默认空实现)                          └ actuator: expose health,prometheus      + Custos dashboard JSON)
 BrokerService 调 metrics 埋点              AdminTokenFilter gate /actuator/prometheus
                                            (health 开放)
```

- **broker**:新增 `BrokerMetrics` 接口(纯计数/计时方法)+ `BrokerMetrics.NOOP`。`BrokerService` 构造器加 `BrokerMetrics metrics`(默认可传 NOOP),在 `queryDb`/`issueAndRun` 内用 `System.nanoTime()` 测时 + 调 `metrics.record*`。**零新增依赖**,broker 仍不碰 Micrometer/Spring。
- **app**:`app/pom.xml` 加 `spring-boot-starter-actuator:3.3.2` + `micrometer-registry-prometheus:1.13.2`。新增 `io.custos.app.metrics.MicrometerBrokerMetrics`(implements broker 的 `BrokerMetrics`,持 `MeterRegistry`)+ `MetricsConfig`(注册该 bean + 4 个 gauge,gauge 数据源同 MonitorController:`OperatorService.status()` + `unsealed().leases()/resourceManager()/approvals()`)。`OperatorService` 构造器注入 `BrokerMetrics`(Spring 自动装 Micrometer 实现),`assemble()` 把它传进 `new BrokerService(...)`。
- **AdminTokenFilter / HostConfig**:`/actuator/prometheus` 进 adminPath + urlPatterns;`/actuator/health` 不门控。
- **compose**:`prometheus` 服务(挂 `examples/prometheus/prometheus.yml`,scrape custos 带 `authorization` bearer)+ `grafana` 服务(挂 `examples/grafana/provisioning/` 自动配 datasource + 加载 `custos-dashboard.json`)。

## 3 · 指标清单（低基数,无敏感值)

**Counters**(broker 经 BrokerMetrics 埋):
| 指标(Micrometer 名) | Prometheus 名 | tag | 含义 |
|---|---|---|---|
| `custos.decisions` | `custos_decisions_total` | `decision`=allow\|deny\|require-approval\|allow-approved | PDP 决策分流计数 |
| `custos.credentials.issued` | `custos_credentials_issued_total` | — | 动态凭证签发次数 |
| `custos.credentials.revoked` | `custos_credentials_revoked_total` | — | 即用即焚撤销次数 |
| `custos.approvals` | `custos_approvals_total` | `action`=created\|approved\|denied\|consumed | 审批动作计数 |

**Timers**(broker 埋):
| `custos.query.duration` | `custos_query_duration_seconds` | secretless 查询执行耗时 |
| `custos.pdp.decision.duration` | `custos_pdp_decision_duration_seconds` | PDP 决策耗时 |
| `custos.credential.issue.duration` | `custos_credential_issue_duration_seconds` | 凭证签发耗时 |
| `custos.credential.revoke.duration` | `custos_credential_revoke_duration_seconds` | 凭证撤销耗时 |

**Gauges**(app 注册,读运行态):
| `custos.seal.sealed` | `custos_seal_sealed` | 1=sealed/0=unsealed |
| `custos.leases.active` | `custos_leases_active` | 活跃租约数 |
| `custos.resources.count` | `custos_resources_count` | 已注册资源数 |
| `custos.approvals.pending` | `custos_approvals_pending` | 待审批队深 |

**脱敏铁律**:tag 仅用有界枚举(decision/action),**绝不**用 agent/resource 名/SQL/token 作 label(高基数 + 泄露);指标值只是计数/耗时,从不含凭证。沿用 JVM/HTTP 默认指标(actuator 自带)即可,不额外暴露。

## 4 · 鉴权与暴露

- `application.yml`(或等价):`management.endpoints.web.exposure.include=health,prometheus`;`management.endpoint.health.show-details=never`(只回 status,不泄组件细节/seal 态)。
- `AdminTokenFilter`:adminPath 加 `path.startsWith("/actuator/prometheus")`(**只门控 prometheus,放行 health**);`HostConfig` urlPatterns 加 `/actuator/prometheus`(不加 `/actuator/*` 以免连带 health)。
- 无 token GET `/actuator/prometheus` → 401;带 admin token → 200 文本(含 `custos_` 指标)。GET `/actuator/health` 无 token → 200 `{"status":"UP"}`。
- compose 的 Prometheus 用 `authorization: { credentials: <admin token> }` scrape config 携 Bearer。

## 5 · compose 监控栈

- `examples/prometheus/prometheus.yml`:一个 job `custos`,`metrics_path: /actuator/prometheus`,`authorization` Bearer = compose 的 `CUSTOS_ADMIN_TOKEN`(demo-token),target `custos:8080`,scrape_interval 15s。
- `examples/grafana/provisioning/datasources/prometheus.yml`:自动配 Prometheus datasource(`http://prometheus:9090`)。
- `examples/grafana/provisioning/dashboards/custos.yml` + `examples/grafana/dashboards/custos-dashboard.json`:预置「Custos 概览」仪表盘——决策速率(按 decision 分)、近窗拒绝率、查询/PDP 延迟分位、凭证签发·撤销速率、活跃租约 gauge、seal 态 stat、审批队深。
- compose 加两服务:`prometheus`(image prom/prometheus,挂 prometheus.yml,端口 9090)、`grafana`(image grafana/grafana,挂 provisioning,端口 3001:3000,匿名只读或默认 admin/admin 本地用),均 `depends_on: custos`。

## 6 · 测试（TDD)

- **broker `BrokerMetricsTest`**(纯单元):一个 capturing fake `BrokerMetrics` 记录被调的 decision/action/计时次数;`BrokerService` 走 allow/deny/require-approval/approved-retry 各路径后,断言 fake 收到对应 `recordDecision`/`recordApproval`/`recordCredentialIssued`/计时调用(用现有 BrokerServiceIT 的装配,注入 capturing metrics)。NOOP 路径不报错。
- **app `MetricsEndpointIT`**(Spring+Testcontainers,照 ConsoleReadEndpointsIT 模板):解封后 ① 无 token GET `/actuator/prometheus` → 401;② 带 admin token → 200 且 body 含 `custos_decisions_total` 等(经一次 query_db 后);③ GET `/actuator/health` 无 token → 200 `status=UP`;④ gauge `custos_seal_sealed` 在 body 中(解封后值 0)。
- **回归**:BrokerService 构造器加 BrokerMetrics 参数后,现有 broker/app 测试随动(传 NOOP 或 capturing),`mvn -B clean verify` 全绿。

## 7 · 交付物

- broker:`BrokerMetrics` SPI + NOOP;`BrokerService` 埋点 + 构造器加 metrics 参;`BrokerMetricsTest`。
- app:actuator+micrometer 依赖;`MicrometerBrokerMetrics` + `MetricsConfig`(bean + 4 gauge);`OperatorService` 注入并传入 BrokerService;AdminTokenFilter/HostConfig 门控 `/actuator/prometheus`;`application.yml` management 暴露;`MetricsEndpointIT`。
- compose:prometheus + grafana 两服务 + `examples/prometheus/`、`examples/grafana/` 配置与仪表盘 JSON。
- `examples/demo.md`:可观测段(开 Grafana 3001 看 Custos 仪表盘;curl `/actuator/prometheus` 带 admin token)。
- M17 看板卡 done。

## 8 · 验收标准

- `docker compose up` 后:`curl -H "Authorization: Bearer demo-token" localhost:8080/actuator/prometheus` 回含 `custos_decisions_total`/`custos_seal_sealed` 等;无 token → 401;`/actuator/health` 无 token → 200 UP。
- 浏览器开 Grafana(localhost:3001)→ Custos 仪表盘显示决策速率/延迟/租约/seal 态真实曲线(跑几次 query_db 后有数据)。
- broker 仍不依赖 Micrometer/Spring(`mvn -pl broker dependency:tree` 无 micrometer)。
- `mvn -B clean verify` 全绿(含 BrokerMetricsTest + MetricsEndpointIT + 契约回归)。

## 9 · 不做（YAGNI)

- 不做分布式 tracing(OpenTelemetry span)——留后。
- 不做高基数 label(per-agent/per-resource/per-SQL),只用有界枚举 tag。
- 不往 Nacos 推指标(那是 M18 Nacos 深接范畴)。
- 不写告警规则(Alertmanager)——只给基础 Grafana 仪表盘;告警留运维按需配。
- 不把 console 监控页改成嵌 Grafana——console 仍用 /monitor/stats 卡片,Grafana 是深挖入口。
- 不暴露除 health/prometheus 外的 actuator 端点(env/beans/heapdump 等一律不开)。
