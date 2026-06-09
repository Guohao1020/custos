# Custos MVP v0.1 Demo Runbook

> 本 runbook 把端到端演示与验收标准 AC1–AC8 固化为可执行步骤。CLI 子命令名（`custos operator ...`
> 等）为约定式占位，完整 CLI/Bean 装配随 app 接线落地；核心能力（密文存储、解封、动态凭证、
> secretless、哈希链审计、jCasbin 决策、Nacos 秒级吊销）均已在各模块单测/集成测试中验证。

## 启动

```bash
docker compose -f examples/docker-compose.yml up -d --build
# 等 mysql/nacos healthy
```

## 1. 解封引擎（AC1）

```bash
# init 一次，记下 5 个 unseal 分片；提交其中 3 个解封
custos operator init --shares 5 --threshold 3      # 输出 5 个分片（仅此一次）
custos operator unseal <share1>
custos operator unseal <share2>
custos operator unseal <share3>
custos operator status                              # 期望 sealed=false
```

**通过标准**：缺片时 status 显示 progress<3 且操作被拒；满 3 片后 unsealed。
（对应实现与测试：`engine` 模块 `DefaultSealManager` + `ShamirSplitter`，`DefaultSealManagerTest`、
`JimmerSealStoreIT` 已验证 init 后保持 sealed、跨实例从 MySQL 恢复解封。）

## 2. 写策略到 Nacos（允许 claude-prod 只读）

```bash
custos policy put --data-id custos-policy --content '
p, role:reader, tool:db/*, read, allow
g, agent:claude-prod, role:reader'
```

## 3. Agent 经 MCP 查询（AC3/AC4/AC5）

- 在 MCP 客户端（Claude/Codex）配置 custos-broker（stdio）。
- 调用 `query_db { tool:"db/query_orders", schema:"appdb", sql:"SELECT COUNT(*) AS n FROM appdb.orders", userToken:"<JWT>" }`
- 期望返回 `rows=[{n=3}]`。

**验收**：
- AC3 动态凭证：查询期间 MySQL 出现临时 `v_ro_*` 只读账号，查询后被 DROP
  （`SELECT user FROM mysql.user` 验证）。已由 `DynamicDbCredentialsIT` 覆盖。
- AC4 secretless：抓 MCP 往返报文/日志，**无连接串/密码**，只见结果。已由 `BrokerServiceIT`
  断言结果序列化不含 `v_ro_`/`password`。
- AC5 可解释：被拒时返回命中策略 + 原因。已由 `CasbinPdpTest` 覆盖。

## 4. 秒级吊销（AC6）

```bash
# 改策略为拒绝，计时到下一次查询被拒
time custos policy put --data-id custos-policy --content '
p, role:reader, tool:db/*, read, deny
g, agent:claude-prod, role:reader'
# 立即重发 query_db → 期望 DENIED；记录从改策略到被拒的延迟（应 ≤ 数秒）
```

**通过标准**：AC6 延迟 ≤ 数秒（记录 P95）。逻辑已由 `RevocationViaWatcherTest`（内存控制面，
毫秒级翻转）验证；真实 Nacos 推送由 `NacosControlPlaneSmokeIT` 在本 stack 起来后验证（见末尾）。

## 5. 审计防篡改（AC7）

```bash
custos audit verify                 # 期望 OK
# 手工改一条历史：UPDATE custos_audit SET action='write' WHERE seq=1;
custos audit verify                 # 期望报断链于 seq=1
```

（对应实现与测试：`HashChainAuditLog` + `HashChainAuditLogIT`，已验证篡改即断链并定位 seq。）

## 6. 落盘加密（AC2）

```bash
# 直查存储表，确认密文
docker exec -it <mysql> mysql -uroot -prootpwd -e "SELECT HEX(svalue) FROM custos.custos_storage LIMIT 1"
```

**通过标准**：均为密文；改一字节后读取报完整性失败。（对应 `JimmerStorageIT` 已断言落盘为密文、
`IntlSuiteAeadTest` 验证 GCM tag 篡改即 IntegrityException。）

## 7. 一键起（AC8）

`docker compose up` 起 Nacos+MySQL+Custos，按上述脚本跑通即 AC8 通过。

## 附：验证真实 Nacos 秒级推送（计划 4 的环境门控 IT）

stack 起来后，本地对着 compose 暴露的 Nacos 跑：

```bash
NACOS_ADDR=127.0.0.1:8848 mvn -q -pl authz test -Dtest=NacosControlPlaneSmokeIT
```

期望 PASS（publish→get→subscribe 收到秒级变更推送）。无 `NACOS_ADDR` 时该 IT 自动跳过。
