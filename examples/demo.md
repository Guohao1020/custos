# Custos MVP v0.1 Demo Runbook

> 端到端演示与验收 AC1–AC8 的可执行步骤。`custos` CLI 即 `custos-cli` 模块（picocli），打 host 的 REST admin；
> 也可直接用 `curl`。核心能力（密文存储、解封、动态凭证、secretless、哈希链审计、jCasbin 决策、Nacos 秒级吊销）
> 均已在各模块单测/集成测试（含 `HostEndToEndIT`）中验证。

## 启动

```bash
docker compose -f examples/docker-compose.yml up -d --build
# 等 mysql/nacos/custos healthy；admin token = demo-token（见 compose）
export TOKEN=demo-token
```

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

## 2. 写策略到 Nacos（允许 claude-prod 只读）

```bash
custos --token $TOKEN policy --content 'p, role:reader, default, tool:db/*, read, allow
g, agent:claude-prod, role:reader, default'
```
> 策略含 `dom` 列（=Nacos namespace，多租户隔离）；单租户演示用 `default`。

## 3. Agent 经 MCP / REST 查询（AC3/AC4/AC5）

- **MCP**：MCP 客户端（Claude/Codex）以 stdio 启动 custos-broker（`custos.transport.mcp-stdio=true` 且已解封），调用
  `query_db { tool:"db/query_orders", schema:"appdb", sql:"SELECT COUNT(*) AS n FROM appdb.orders", userToken:"<JWT>" }`。
- **REST**（等价，便于演示）：
```bash
curl -s -XPOST localhost:8080/query_db -H 'Content-Type: application/json' \
  -d '{"tool":"db/query_orders","schema":"appdb","sql":"SELECT COUNT(*) AS n FROM appdb.orders","userToken":"<JWT>"}'
# 期望 {"allowed":true,"rows":[{"n":3}],...}
```
- **CLI**（等价）：
```bash
custos query --tool db/query_orders --schema appdb --sql "SELECT COUNT(*) AS n FROM appdb.orders" --user-token <JWT>
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

```bash
custos --token $TOKEN audit verify                 # {"ok":true,...}
# 手工改一条历史：UPDATE custos_audit SET action='write' WHERE seq=1;
custos --token $TOKEN audit verify                 # {"ok":false,"brokenAtSeq":1}
```

（实现/测试：`HashChainAuditLog` + `HashChainAuditLogIT`，已验证篡改即断链并定位 seq。）

## 6. 落盘加密（AC2）

```bash
docker exec -it <mysql> mysql -uroot -prootpwd -e "SELECT HEX(svalue) FROM custos.custos_storage LIMIT 1"
```

**通过标准**：均为密文；改一字节后读取报完整性失败。（`JimmerStorageIT` 断言落盘密文、`IntlSuiteAeadTest` 验证 GCM tag 篡改即 IntegrityException。）

## 7. 一键起（AC8）

`docker compose up` 起 Nacos+MySQL+Custos，按上述脚本跑通即 AC8 通过。

## 附：验证真实 Nacos 秒级推送（计划 4 的环境门控 IT）

stack 起来后，本地对着 compose 暴露的 Nacos 跑：

```bash
NACOS_ADDR=127.0.0.1:8848 mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT
```

期望 PASS（publish→get→subscribe 收到秒级变更推送）。无 `NACOS_ADDR` 时该 IT 自动跳过。
