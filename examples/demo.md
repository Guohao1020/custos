# Custos MVP v0.1 Demo Runbook

> 端到端演示与验收 AC1–AC8 的可执行步骤。`custos` CLI 即 `custos-cli` 模块（picocli），打 host 的 REST admin；
> 也可直接用 `curl`。核心能力（密文存储、解封、动态凭证、secretless、哈希链审计、jCasbin 决策、Nacos 秒级吊销）
> 均已在各模块单测/集成测试（含 `HostEndToEndIT`）中验证。

## 启动

```bash
docker compose -f examples/docker-compose.yml up -d --build
# 等 mysql/nacos healthy → nacos-init 一次性初始化管理员 → custos 起；admin token = demo-token（见 compose）
export TOKEN=demo-token
```

> **Nacos 3.2**：API 鉴权开启（custos 以 `nacos/DemoPass123` 连接）；控制台从 8848 分离到独立端口，
> Web UI（含 **AI 管理中心**：MCP 注册中心 / Agent / Skill 管理）在 http://localhost:8081 ，
> 登录账号 `nacos` / `DemoPass123`（由 `nacos-init` 服务经一次性 `POST /v3/auth/user/admin` 初始化）。

CLI 构建：`mvn -pl cli -am -DskipTests package`，入口 `io.custos.cli.CustosCli`（或 `java -jar cli/target/custos-cli-*.jar`）。

## 1. 解封引擎（AC1）

```bash
# init 一次，记下 5 个 base64 分片；提交其中 3 个解封
custos --token $TOKEN operator init --shares 5 --threshold 3   # {"shares":["...","...",...]}（仅此一次）
custos --token $TOKEN operator unseal <share1>
custos --token $TOKEN operator unseal <share2>
custos --token $TOKEN operator unseal <share3>
custos --token $TOKEN operator status                          # {"sealed":false,...}
# 等价 curl：
# curl -s -XPOST localhost:8080/operator/init -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"shares":5,"threshold":3}'
```

**通过标准**：缺片时 status 仍 sealed（progress<3）且查询被拒；满 3 片后 unsealed。
（实现/测试：`OperatorService` + `HostEndToEndIT`：init 后 sealed、满 3 片解封并装配运营组件。）

## 1.5 注册受治理资源（AC0 · 资源接入）

去硬编码后，目标库须显式注册：管理员提供连接串 + **高权限管理凭证**，custos 用它现场签发即用即焚只读凭证。高权限凭证整条经 Barrier 加密落盘（见 AC9）。

```bash
custos --token $TOKEN resource register \
  --name appdb --type db.relational --dialect mysql \
  --jdbc-url jdbc:mysql://mysql:3306/appdb \
  --admin-user custos --admin-password custospwd --role read-only
custos --token $TOKEN resource list          # ["appdb"]（脱敏，无密码）
```
> 等价 REST：`POST /resources`（admin-gated）。MySQL/PostgreSQL 走内置适配器；罕见库用 `--role` 的 SQL 模板（creation/revocation）。

**通过标准**：注册后 `resource list` 含 appdb；落盘记录为密文（AC9）。

## 2. 写策略到 Nacos（允许 claude-prod 只读）

```bash
custos --token $TOKEN policy --content 'p, role:reader, default, tool:db/*, read, allow
g, agent:claude-prod, role:reader, default'
```
> 策略含 `dom` 列（=Nacos namespace，多租户隔离）；单租户演示用 `default`。

## 3. Agent 经 MCP / REST 查询（AC3/AC4/AC5）

> 先取一枚 `userToken`：生产由 Agent 经身份注册 / OBO 委托获取；demo 用 admin-gated 的开发端点现签一枚
> （由 host 私钥 ES256 签名，受 Bearer admin token 保护）：
> ```bash
> JWT=$(curl -s -XPOST localhost:8080/token/issue -H "Authorization: Bearer $TOKEN" \
>   -H 'Content-Type: application/json' -d '{"agent":"claude-prod","scopes":["tool:db/query_orders"]}' \
>   | python3 -c "import sys,json;print(json.load(sys.stdin)['jwt'])")
> ```

- **MCP**：MCP 客户端（Claude/Codex）以 stdio 启动 custos-broker（`custos.transport.mcp-stdio=true` 且已解封），调用
  `query_db { tool:"db/query_orders", resource:"appdb", role:"read-only", sql:"SELECT COUNT(*) AS n FROM appdb.orders", userToken:"<JWT>" }`。
- **REST**（等价，便于演示）：
```bash
curl -s -XPOST localhost:8080/query_db -H 'Content-Type: application/json' \
  -d "{\"tool\":\"db/query_orders\",\"resource\":\"appdb\",\"role\":\"read-only\",\"sql\":\"SELECT COUNT(*) AS n FROM appdb.orders\",\"userToken\":\"$JWT\"}"
# 期望 {"allowed":true,"rows":[{"n":3}],"denyReason":null}
```
- **CLI**（等价）：
```bash
custos query --tool db/query_orders --resource appdb --sql "SELECT COUNT(*) AS n FROM appdb.orders" --user-token $JWT
# 一键重新密封（admin）：custos --token $TOKEN operator seal
```

**验收**：
- AC3 动态凭证：查询期间 MySQL 出现临时 `v_ro_*` 只读账号，查询后被 DROP（`SELECT user FROM mysql.user` 验证）。由 `DynamicDbCredentialsIT` 覆盖。
- AC4 secretless：抓 MCP/REST 往返报文，**无连接串/密码**，只见结果。由 `BrokerServiceIT`/`HostEndToEndIT` 断言结果不含 `v_ro_`/`password`。
- AC5 可解释：被拒时返回命中策略 + 原因。由 `CasbinPdpTest` 覆盖。

## 4. 秒级吊销（AC6）

```bash
# 改策略为拒绝，计时到下一次查询被拒
time custos --token $TOKEN policy --content 'p, role:reader, default, tool:db/*, read, deny
g, agent:claude-prod, role:reader, default'
# 立即重发 query_db → 期望 {"allowed":false,...}；记录从改策略到被拒的延迟（应 ≤ 数秒）
```

**通过标准**：AC6 延迟 ≤ 数秒（记录 P95）。逻辑由 `RevocationViaWatcherTest`（内存控制面，毫秒级翻转）验证；
真实 Nacos 推送由 `NacosControlPlaneSmokeIT` 在本 stack 起来后验证（见末尾）。

## 5. 审计防篡改（AC7）

> 每次 `query_db` 决策（allow/deny）都会写一条哈希链审计行（actor=agent、resource=tool，
> 敏感原文/租约号经 HMAC 脱敏，绝不明文入库）；下面在已跑过若干查询后校验链。

```bash
custos --token $TOKEN audit verify                 # {"ok":true,"brokenAtSeq":-1}
# 手工改一条历史：UPDATE custos_audit SET action='write' WHERE seq=1;
custos --token $TOKEN audit verify                 # {"ok":false,"brokenAtSeq":1}
```

（实现/测试：`HashChainAuditLog` + `HashChainAuditLogIT`，已验证篡改即断链并定位 seq。）

## 6. 落盘加密（AC2）

```bash
docker exec -it <mysql> mysql -uroot -prootpwd -e "SELECT HEX(svalue) FROM custos.custos_storage LIMIT 1"
```

**通过标准**：均为密文；改一字节后读取报完整性失败。（`JimmerStorageIT` 断言落盘密文、`IntlSuiteAeadTest` 验证 GCM tag 篡改即 IntegrityException。）

## 6.5 高权限密钥托管（AC9）

资源的高权限管理凭证（`custos`/`custospwd`）整条记录经 Barrier 加密落盘，绝不明文、绝不进 Nacos：

```bash
# 资源记录在 custos_storage 的 resource/appdb 键，值为密文：
docker exec <mysql> mysql -ucustos -pcustospwd -N -e \
  "SELECT HEX(svalue) FROM custos.custos_storage WHERE skey='resource/appdb'"   # 一串密文 hex
docker exec <mysql> mysql -ucustos -pcustospwd -N -e \
  "SELECT svalue FROM custos.custos_storage WHERE skey='resource/appdb'" | grep -i custospwd   # 无命中
custos --token $TOKEN resource list            # 列表脱敏，无密码
custos --token $TOKEN resource rotate-admin --name appdb --admin-password <new>   # 轮换：旧密文换新
```

**通过标准**：落盘 grep 不到明文高权限密码；REST/CLI 响应不回显密码；轮换后旧密文失效。
（实现/测试：`ResourceStoreIT` 断言落盘无明文密码；`ResourceControllerIT` 断言响应脱敏。）

## 7. 一键起（AC8）

`docker compose up` 起 Nacos+MySQL+Custos（compose 项目名 `custos-demo`），按上述脚本跑通即 AC8 通过。

## 8. 真实 Claude 经 MCP 调 custos（secretless 全链路）

拓扑：Claude 作为 MCP client 拉起**第二个 custos host**（stdio 传输，与容器实例共享同一
MySQL 存储与 Nacos 控制面）。[mcp-custos.sh](mcp-custos.sh) 负责：进程 sealed 启动 →
后台用 `.e2e-shares.json` 的同一组 Shamir 分片经 REST(:8090) 解封 → 签发 agent 令牌写
`.e2e-local-jwt.txt`（agent 只拿令牌，永远见不到 DB 凭证）。

- **交互式（Claude Code / Claude Desktop）**：把 [claude-mcp.json](claude-mcp.json) 的
  `mcpServers.custos` 合入 MCP 配置（CLI：`claude --mcp-config examples/claude-mcp.json`），
  然后让 Claude 读取令牌文件并调用 `query_db`。
- **无交互冒烟**：`python examples/mcp_smoke_client.py "SELECT id, amount FROM appdb.orders ORDER BY id"`
  —— 完整走 initialize → tools/list → tools/call。

实测输出（2026-06-10）：握手 `custos-broker 0.1.0` → `rows=[{id=1, amount=100}, …]`、
`rows=[{total=600}]`；两次决策以 `agent:claude-prod allow` 追加到共享审计链，
容器实例 `audit verify` 校验跨 host 链完整（`ok:true`）。

> 注：MCP server 支持 sealed 启动（握手/tools-list 可用，工具调用返回 SEALED 提示），
> 解封后无需重启即可服务；stdio 模式下 banner/控制台日志已关闭以保 JSON-RPC 通道纯净。

## 9. 审批闭环（AC10）

三态决策的第三态：`AbacPdp` 判出 `REQUIRE_APPROVAL`（风险分落在 approvalThreshold ~ denyThreshold 之间）时，
broker 不直接拒，而是把待审批请求落 `custos_approval` 表并回 `{"status":"PENDING","approvalId":"..."}`；
运维经 `/approvals` 队列 approve 后，agent 在 15 分钟有效窗内携 `approvalId` 重发即放行（单次有效，防重放）。

> **触发条件**：demo 默认策略是直接 `allow`，不会触发第三态。要演示需让 PDP 对该请求判出
> `REQUIRE_APPROVAL`——即 `RiskScorer` 给出的风险分介于 `approvalThreshold` 与 `denyThreshold` 之间
> （配置见 ABAC 设计 spec `docs/superpowers/specs/2026-06-09-custos-abac-design.md`）。
> 下面以 REST 演示完整闭环；若你的环境策略未触发第三态，可直接从第 2 步（列队列）观察经 `BrokerServiceIT`/`ApprovalFlowIT` 落库的审批单。

```bash
# 1) 触发中风险请求 → 返回 PENDING + approvalId（而非 denied）
curl -s -XPOST localhost:8080/query_db -H 'Content-Type: application/json' \
  -d "{\"tool\":\"db/query_orders\",\"resource\":\"appdb\",\"role\":\"read-only\",\"sql\":\"SELECT amount FROM appdb.orders WHERE id=1\",\"userToken\":\"$JWT\"}"
# 期望 {"status":"PENDING","rows":[],"denyReason":"awaiting approval","approvalId":"<id>"}
APPROVAL_ID=<上一步返回的 approvalId>

# 2) 运维列出 pending 审批队列（admin-gated）
curl -s http://localhost:8080/approvals -H "Authorization: Bearer $TOKEN"
# 期望 [{"id":"<id>","agent":"agent:claude-prod","tool":"db/query_orders","resource":"appdb","role":"read-only","risk":<n>,"reason":"...","status":"PENDING",...}]

# 3) 批准该单（给 15 分钟有效窗）
curl -s -XPOST "http://localhost:8080/approvals/$APPROVAL_ID/approve" -H "Authorization: Bearer $TOKEN"
# 期望 {"id":"<id>","status":"approved"}

# 4) agent 携 approvalId 在窗内重发 → 放行签发并查得数据
curl -s -XPOST localhost:8080/query_db -H 'Content-Type: application/json' \
  -d "{\"tool\":\"db/query_orders\",\"resource\":\"appdb\",\"role\":\"read-only\",\"sql\":\"SELECT amount FROM appdb.orders WHERE id=1\",\"userToken\":\"$JWT\",\"approvalId\":\"$APPROVAL_ID\"}"
# 期望 {"status":"ALLOWED","rows":[{"amount":100}],"denyReason":null,"approvalId":null}

# 5) 审计链仍完整：require-approval / approve / allow-approved 三条决策落链不断链
custos --token $TOKEN audit verify                 # {"ok":true,"brokenAtSeq":-1}
```

**通过标准**（AC10）：
- 中风险首次请求返回 `status=PENDING + approvalId`（不是 denied）；`GET /approvals` 能列出该 pending。
- approve 后 agent 携 id 重发签发成功并查得数据；deny / 超时（>15min）/ 资源不一致 / 已消费重放均 denied。
- `/approvals*` 缺 Bearer admin token → 401。
- 三条审批动作审计（require-approval / approve / allow-approved）落哈希链，`audit verify` 仍 `ok:true`。

（实现/测试：engine `JimmerApprovalStoreIT`、broker 审批流 IT、app `ApprovalFlowIT` 覆盖落库→列队列→approve→携 id 放行→单次消费防重放全链。）

## 10. 后台管理控制台（M16）

`docker compose up` 时一并起 `console` 服务（nginx 服 Vue 构建产物 + 反代 admin API），
让安全团队"看得见"系统运行态：审计链浏览、实时监控、运维动作、资源 GUI、审批队列统一面板。

```bash
# compose 已含 console 服务，无需额外步骤；起来后浏览器开：
#   http://localhost:3000
# 登录页填 admin token —— 与 custos 服务的 CUSTOS_ADMIN_TOKEN 一致（demo 为 demo-token，见 compose）。
```

登录后各面板：

- **实时监控**：卡片显示封印态（SEALED/UNSEALED）、活跃租约数、资源数、审计总数、决策计数（allow/deny/require-approval）、近窗拒绝率；每 5 秒轮询 `GET /monitor/stats`。
- **审计浏览**：表格列 seq/时间/actor/决策/资源/result，可按 agent / decision 过滤 + 分页（`GET /audit`）；顶部"链完整性"徽章调 `GET /audit/verify`，断链显示 `brokenAtSeq`。
- **运维动作**：逐片提交 Shamir 分片解封（进度 n/threshold）、密封（调 `/operator/*`）。
- **资源配置**：列已注册资源 + 注册表单（`POST /resources`）+ rotate-admin + 删除；**高权限密码字段提交后立即清空、不回显、不入 localStorage**。
- **审批队列**：列 pending 审批单 + approve/deny（接 M20 的 `/approvals*`）。

> **安全姿态**：console 不持任何后端密钥，只持运维登录时填入的 admin token——存于 **sessionStorage，刷新即清**，
> 经 axios 拦截器以 `Authorization: Bearer <token>` 出站；401 自动清 token 回登录页。
> compose 内 console 经 nginx 同源反代 `/api/ → custos:8080`，不触发跨域；
> dev 模式（Vite 5173 直连 8080）由 host 的 `custos.console.origin` CORS 白名单放行（不用通配符）。

## 附：验证真实 Nacos 秒级推送（计划 4 的环境门控 IT）

stack 起来后，本地对着 compose 暴露的 Nacos 跑：

```bash
NACOS_ADDR=127.0.0.1:8848 NACOS_USERNAME=nacos NACOS_PASSWORD=DemoPass123 \
  mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT
```

期望 PASS（publish→get→subscribe 收到秒级变更推送）。无 `NACOS_ADDR` 时该 IT 自动跳过；
Nacos 3.x API 鉴权开启时必须带 `NACOS_USERNAME`/`NACOS_PASSWORD`。
