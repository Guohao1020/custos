---
id: SPEC-M16-ADMIN-CONSOLE
type: spec
title: "M16 后台管理控制台（Admin Console）设计"
status: reviewing
date: 2026-06-11
desc: "全自建独立 Vue 3 + Element Plus 控制台 + 支撑只读端点：审计链浏览、实时监控、运维动作、资源配置 GUI、审批队列（踩 M20 闭环）"
---

# M16 后台管理控制台（Admin Console）设计

## 0 · 背景与动机

让安全团队"看得见"：当前审计链能 `verify` 却不能浏览，seal/租约/动态账号/决策速率零监控，
运维与资源配置全靠 curl。M16 补一个全自建独立控制台 + 支撑只读端点，把 custos 的运行态、
审计、运维、资源接入统一到一个面板。承接定位 spec `2026-06-11-positioning-and-docs.md` §4.1
（console 全自建，审计链浏览/secretless 监控/运维/资源 GUI 是 custos 特有，Nacos 控制台给不了）。

## 1 · 已确认的关键决策

| # | 决策 | 取舍 |
|---|---|---|
| D1 | **Vue 3 + Element Plus** SPA | 中文企业后台主流、组件齐、与 Nacos 生态贴；表格/表单/抽屉现成 |
| D2 | **独立 `console/` 工程，分开部署** | 不是 Maven 模块；自有 npm/Vite 构建；host 开 CORS 放行 console 源 |
| D3 | compose 加 **`console` 服务（nginx 服构建产物）** | 独立容器/源，但 `docker compose up` 仍带 console = 完体 |
| D4 | console **持 admin token**（登录页填 → sessionStorage → Bearer） | v0.6 简化；正式 SSO 留后 |
| D5 | **审批闭环后端拆为 M20**（前置） | 审批队列面板调 M20 REST；M16 不含审批持久化后端 |

## 2 · 架构与组件

```
console/ (Vue3+Vite+ElementPlus, 独立工程)         custos-host (app, REST)
  ├─ 登录(token) → sessionStorage                    ├─ [新] CORS 配置(放行 console 源)
  ├─ axios 拦截器：加 Bearer / 401→登录              ├─ [新] GET /audit (分页/过滤)
  └─ 6 视图(见 §4)  ──fetch(JSON)──────────────────▶ ├─ [新] GET /leases
                                                      ├─ [新] GET /monitor/stats
  nginx 服 dist (compose console 服务)               ├─ [新] GET /policy
                                                      ├─ 复用 /operator /audit/verify /resources
                                                      └─ 审批面板 → M20 的 /approvals*（前置）
```

- **console 工程结构**：`console/{package.json, vite.config.ts, src/{api/, views/, components/, router, main.ts}}`。Element Plus 按需引入。`src/api/client.ts` 封装 axios（baseURL=host、Bearer 拦截器）。
- **host 后端（app 模块）**：新增 `MonitorController`/`AuditQueryController`/`LeaseController`（或并入现有 controller）+ `CorsConfig`。读端点经 `OperatorService.unsealed()` 取 storage/audit/resourceManager。

## 3 · 后端只读端点（M16 自己的后端）

| 端点 | 作用 | 数据源 | 备注 |
|---|---|---|---|
| `GET /audit?agent=&decision=&from=&to=&page=&size=` | 审计分页/过滤浏览 | custos_audit 行 | `sensitive_hmac` 已脱敏，可安全展示；engine `AuditLog` 加只读分页查询方法 |
| `GET /audit/verify` | 链完整性（复用现有） | 全链校验 | 已存在 |
| `GET /leases` | 活跃租约列表 | custos_lease | `LeaseManager` 加 `list()`（仅未撤销/未到期） |
| `GET /monitor/stats` | 监控统计 | 聚合 | seal 态 + 审计总数 + 活跃租约数 + 资源数 + 决策 allow/deny/approve 计数 + 近窗拒绝率 |
| `GET /policy` | 当前策略文本 | ControlPlane.get(dataId) | 只读展示生效策略 |

- 全部 **admin-gated**：`/leases`、`/monitor` 补进 `AdminTokenFilter` 的 adminPath 与 urlPatterns（`/audit`、`/policy`、`/resources` 已在）。
- **CORS**：Spring `WebMvcConfigurer` 放行 `custos.console.origin`（可配，默认 `http://localhost:5173` dev + compose console 源），允许 Authorization 头。

## 4 · console 面板（Vue 视图）

1. **登录**：填 admin token → sessionStorage；无 token 全局重定向到此。
2. **审计浏览器**：表格（seq/时间/actor/决策/资源/result_digest）+ 过滤（agent/decision/时间区间）+ 分页；顶部"链完整性"徽章（调 `/audit/verify`，断链显示 `brokenAtSeq`）。
3. **实时监控**：卡片（seal 态、活跃租约、资源数、决策 allow·deny·approve、近窗拒绝率），轮询 `/monitor/stats`。
4. **运维动作**：解封（逐片提交，进度 n/threshold）、密封、轮换主密钥（调 `/operator/*`；轮换主钥若 REST 未备则标 roadmap）。
5. **资源 GUI**：表格（`GET /resources`）+ 注册表单（POST /resources，含高权限密码字段，提交后不回显）+ rotate-admin + 删除。包 M15 REST。
6. **审批队列**：pending 表 + approve/deny 按钮，调 **M20** 的 `GET /approvals`、`POST /approvals/{id}/approve|deny`。**M20 为前置**：未就绪时此面板显示"审批闭环（M20）未启用"。

## 5 · 鉴权与安全

- console 不持任何后端密钥，只持运维填入的 admin token（sessionStorage，刷新即清）。
- 所有 fetch 经 axios 拦截器加 `Authorization: Bearer <token>`；401 清 token 回登录页。
- 注册资源表单的高权限密码：仅随请求 body 出站，**不回显、不存 localStorage、不打印**。
- CORS 只放行可配的 console 源 + Authorization 头，不用通配符。

## 6 · 测试

- **后端 IT**（app，Testcontainers）：`/audit` 分页+过滤正确；`/leases` 只回活跃；`/monitor/stats` 计数与种入数据一致；全部无 token → 401。
- **前端**：Vitest 单测 `api/client`（Bearer 注入、401 处理）+ 关键组件（审计表过滤、监控卡渲染、资源注册表单不回显密码）；可选 Playwright 冒烟（登录→看审计→注册资源）。
- **审批面板 e2e** 依赖 M20，M20 就绪后补。

## 7 · 交付物

- `console/`（Vue3 工程：登录 + 6 视图 + api client + router）
- app：`CorsConfig` + 审计分页查询/租约/监控/策略只读端点 + AdminTokenFilter 扩 adminPath
- engine：`AuditLog` 只读分页查询、`LeaseManager.list()`
- `examples/docker-compose.yml`：加 `console` 服务（nginx 服 dist）
- M20 看板卡（审批闭环，前置）+ M16 看板卡

## 8 · 验收标准

- `docker compose up` 起 host + console，浏览器开 console 登录后：审计可分页过滤浏览、链校验徽章正确；监控卡显示真实 seal/租约/资源/决策计数；运维可解封；资源可注册/轮换/删且密码不回显。
- 所有只读端点无 token → 401；CORS 仅放行配置的源。
- 后端 IT + 前端单测绿；`mvn -B clean verify` 全绿（host 端点）。
- 审批队列面板在 M20 就绪后能列 pending 并 approve/deny 放行。

## 9 · 不做（YAGNI）

- 审批闭环后端 = **M20**（前置，单独 spec→plan）；本模块只做调它 REST 的面板。
- metrics/tracing = **M17**（监控统计本模块用 DB 聚合即可，Prometheus 留 M17）。
- 不做 console 自身 SSO/多用户（v0.6 用 admin token 登录）。
- 不把 console 打进 app jar（独立工程 + nginx，分开部署）。
- 主密钥轮换 REST 若未备：运维面板该按钮标 roadmap，不在本模块新建轮换引擎。
