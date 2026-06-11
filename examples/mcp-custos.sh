#!/usr/bin/env bash
# MCP stdio 启动器：供 MCP client（如 Claude Code）拉起本地 custos host。
# stdout 归 MCP JSON-RPC 独占（banner/日志全关）；运维动作走后台：等 REST 起 → 用
# .e2e-shares.json 的分片解封 → 签发 agent 令牌写 .e2e-local-jwt.txt（agent 自取）。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=8090
TOKEN_FILE="$ROOT/.e2e-local-jwt.txt"
LOG="$ROOT/.e2e-mcp-wrapper.log"
export CUSTOS_ADMIN_TOKEN=demo-token

rm -f "$TOKEN_FILE"
{
  # 等本进程的 REST 起来
  for i in $(seq 1 60); do
    curl -s -o /dev/null "http://localhost:$PORT/operator/status" -H "Authorization: Bearer demo-token" && break
    sleep 1
  done
  # 解封：与容器实例共享同一 custos 库的 seal 配置，分片通用
  tr ',' '\n' < "$ROOT/.e2e-shares.json" | grep -oE '[A-Za-z0-9+/=]{30,}' | head -3 | while read -r S; do
    curl -s -X POST "http://localhost:$PORT/operator/unseal" \
      -H "Authorization: Bearer demo-token" -H "Content-Type: application/json" \
      -d "{\"share\":\"$S\"}" >> "$LOG" 2>&1
    echo >> "$LOG"
  done
  # 注册 appdb 资源（去硬编码后必须显式注册：高权限凭证 custos/custospwd 经 Barrier 加密托管）
  curl -s -X POST "http://localhost:$PORT/resources" \
    -H "Authorization: Bearer demo-token" -H "Content-Type: application/json" \
    -d '{"name":"appdb","type":"db.relational","dialect":"mysql","jdbcUrl":"jdbc:mysql://localhost:3306/appdb","adminUsername":"custos","adminPassword":"custospwd","roles":[{"name":"read-only","kind":"BUILTIN_READONLY","creationStatements":[],"revocationStatements":[],"defaultTtlSeconds":3600,"schema":"appdb"}]}' >> "$LOG" 2>&1
  echo "resource appdb registered" >> "$LOG"
  # 给 claude-prod 签发会话令牌（须由本实例签发：每个 host 自持签名密钥）
  curl -s -X POST "http://localhost:$PORT/token/issue" \
    -H "Authorization: Bearer demo-token" -H "Content-Type: application/json" \
    -d '{"agent":"claude-prod","scopes":["tool:db/query_orders"]}' \
    | sed 's/.*"jwt":"\([^"]*\)".*/\1/' > "$TOKEN_FILE"
  echo "token issued -> $TOKEN_FILE" >> "$LOG"
} >> "$LOG" 2>&1 &

exec java -jar "$ROOT/app/target/custos-app-0.1.0-SNAPSHOT-exec.jar" \
  --custos.transport.mcp-stdio=true \
  --server.port=$PORT \
  --spring.main.banner-mode=off \
  --logging.level.root=OFF \
  --custos.engine.storage-url=jdbc:mysql://localhost:3306/custos \
  --custos.engine.storage-username=custos \
  --custos.engine.storage-password=custospwd \
  --custos.broker.target-jdbc-url=jdbc:mysql://localhost:3306/appdb \
  --custos.nacos.server-addr=localhost:8848 \
  --custos.nacos.username=nacos \
  --custos.nacos.password=DemoPass123
