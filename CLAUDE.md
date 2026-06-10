# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

**Custos** —— Nacos-native、自托管的 **AI Agent 身份 · 密钥 · 权限**统一引擎(Java 21,Maven 多模块,Apache-2.0)。
对标 Vault/OpenBao 的赛道,但密钥引擎完全自研、Nacos 作控制面、支持国密。

## 硬约束(项目法律,任何实现不得违反)

1. **密钥引擎 100% 自研**:绝不使用/抄袭 Vault(BSL)、OpenBao(MPL)、Infisical-EE 的代码;设计语言可参照(见 docs/research/),代码不可。
2. **绝不自造密码学**:只用经审计的库——JDK `javax.crypto` 与 BouncyCastle。算法经 `CipherSuite` 抽象(IntlSuite=AES-256-GCM/SHA-256/ECDSA-P256;GmSuite=SM4-GCM/SM3/SM2),一键切换。
3. **密钥永不进 LLM 上下文、永不进 Nacos**;主密钥落盘前必经 Barrier 加密(`wrapped_barrier`),内存用后清零(`Zeroize`/`Keyring.destroy`)。
4. 审计为防篡改哈希链(改一行即断链定位 seq)。

## 生产姿态原则(用户明确要求)

**不要用 demo 思维定义产物。** 本地 `docker compose up` 起的就是产品完全体,目标是企业内部生产级使用。
具体含义:命名/文案避免 demo 字样;任何为图省事的简化(如 examples/init 的 `GRANT ALL`、固定 admin token、
`/token/issue` 明文签发口、单节点 Derby Nacos)都必须在 `docs/audit/AUDIT-PREP.md` 的缺口清单(G1-G6)挂账,
并在生产化模块中逐项消除,而不是默认接受。

## 常用命令

```bash
# 全量门禁(合并前必须绿;Testcontainers 需 Docker 运行中)
mvn -B clean verify

# 单模块 / 单测试
mvn -pl engine test
mvn -pl broker test -Dtest=BrokerAuditWiringTest -Dsurefire.failIfNoSpecifiedTests=false

# bench 标签用例默认排除;纳入跑法见 docs/bench/RUNBOOK.md
mvn -pl engine test -DbenchExcluded=

# 全容器栈(MySQL + Nacos 3.2 + custos;e2e 验收步骤见 examples/demo.md 的 AC1-AC8)
docker compose -f examples/docker-compose.yml up -d --build

# MCP 全链路冒烟(拉起第二个 stdio host,共享存储/控制面,自动解封+签令牌)
python examples/mcp_smoke_client.py "SELECT 1"

# 看板重建(docs/index.html;python 是 3.9 太旧,必须 python3;1.0 起 build 更名 render)
python3 -m docs_cockpit render --config docs-cockpit.yaml
```

## 工作节奏

- 每个模块走:brainstorm → spec(docs/superpowers/specs/)→ plan(docs/superpowers/plans/)→ TDD 实现 → `mvn -B verify` 门禁 → FF 合并 main + push → 更新 docs/spec/module/M*.md 看板卡。
- **绝不直接在 main 上开发**:先 `git checkout -b impl/<x>`。
- 第三方 API 写计划前必须源码/文档核准(本仓库已因此纠正过 JRaft 版本、MCP SDK 2.0 API 形状)。

## 架构(跨文件的大图)

模块依赖:`engine ← identity/authz ← broker ← app(custos-host) / cli / sdk`。

**密封生命周期**(app 的核心装配逻辑):host 启动即 **sealed**,只有 `/operator/*` 可用;
`OperatorService` 持 `SealManager` + `AtomicReference<UnsealedContext>`,提交满阈值的 Shamir 分片
(默认 5/3)后才装配 Barrier→`JimmerStorage`→`HashChainAuditLog`→`BrokerService`(见 `OperatorService.assemble()`)。
seal 配置与主密钥密文存 `custos_seal_config`;**engine 不自动建表**,schema 在
`engine/src/main/resources/db/schema.sql`(容器栈由 `examples/init/schema.sql` 建)。

**一次 query_db 的完整路径**(broker = PEP):`tokens.verify(JWT ES256)` → `CasbinPdp.decide`
(RBAC+domain,模型 `r=sub,dom,obj,act`;`AbacPdp` 装饰器加三态 ALLOW/DENY/REQUIRE_APPROVAL)→
`SecretsEngine.issue`(现场 `CREATE USER v_ro_* + GRANT SELECT`)→ `SecretlessQueryExecutor`
(只允许单条 SELECT/WITH)→ finally `revoke`(DROP USER,即用即焚)→ 每次决策(allow/deny)
append 哈希链审计。**凭证从不出现在返回值/日志/LLM 上下文里。**

**策略控制面**:`custos.nacos.server-addr` 为空 → `InMemoryControlPlane`;否则 `NacosControlPlane`
(Nacos 3.x 默认开 API 鉴权,必须带 username/password)。`PolicyWatcher` 订阅 `custos-policy`
dataId,gRPC 推送 → `CasbinPdp.reload`,实测策略翻转到拒绝生效 ~275ms。

**MCP 传输**:`custos.transport.mcp-stdio=true` 时 `McpStdioRunner` 暴露 `query_db` 工具。
进程 **sealed 启动**(broker 经 Supplier 惰性解析,工具调用未解封返回 SEALED 错误);
stdio 模式 stdout 归 JSON-RPC 独占,必须 `--spring.main.banner-mode=off --logging.level.root=OFF`。
多 host 共享同一 MySQL/Nacos 时:Shamir 分片通用、审计链跨 host 连续;但**各 host 自持 JWT 签名密钥**,
令牌必须由处理请求的那个 host 签发(已知缺口,生产化需密钥分发)。

**HA(M11,未接入默认 host 装配)**:`RaftKvServer/RaftStorage/RaftSealStore/RaftLeaseManager`
(SOFAJRaft),lease 清扫仅 leader 执行。

## 构建/测试踩坑(都是真实付费学到的)

- **Testcontainers**:新 Docker Engine MinAPIVersion=1.40,docker-java 默认低于此 → HTTP 400。
  各模块 surefire/failsafe 已钉 `<api.version>1.40</api.version>`,新模块加 Docker 测试时记得带上。
- **jackson 版本必须经 parent 的 jackson-bom 管理**:nacos-client 等传递引入旧 jackson-core,
  nearest-wins 出混版本,只在接真 Nacos 时以 `NoClassDefFoundError: StreamConstraintsException` 爆出。
- **spring-boot repackage 必须 `classifier=exec`**:parent 非 spring-boot-starter-parent(无 Boot
  dependencyManagement 自动导入),repackage 不自动绑定;直接绑到主产物会让 failsafe IT 拿到
  BOOT-INF 布局 fat jar 而 NoClassDefFound。thin 主产物给 IT,`-exec.jar` 给容器(Dockerfile 已对齐)。
- **JRaft on Java 21**:需要 `--add-opens java.base/java.util=ALL-UNNAMED --add-opens
  java.base/java.lang=ALL-UNNAMED`(engine 的 argLine 已配);`RaftKvServer.shutdown()` 必须
  `node.join()` 等存储关闭,否则 Windows 下残留 LOCK 句柄。
- **Nacos 3.x**:控制台/AI 管理中心在独立端口(容器 8080 → 宿主 8081),8848 只剩 API;
  无默认管理员密码,headless 初始化是一次性 `POST /v3/auth/user/admin`(之后 409);
  镜像三个 `NACOS_AUTH_*` 必填否则退出 255;客户端 gRPC 端口 = 主端口 +1000。
- Dockerfile 默认 Aliyun maven 镜像(`--build-arg MAVEN_MIRROR=` 可换回 Central)。

## 语言与提交约定(承接全局 CLAUDE.md)

用户可读 prose(回复/PR body/代码注释/commit body)= 中文;机器面 token(标识符/commit subject/
PR title/日志串)= English。commit subject 用 conventional 前缀(feat/fix + 模块 scope)。
