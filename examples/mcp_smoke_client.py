# MCP stdio 冒烟客户端：拉起 examples/mcp-custos.sh，按 MCP 协议握手并调用 query_db。
# 用途：无交互环境下验证 custos 的 MCP server 全链路（解封自举 → 令牌 → 查询）。
# 用法：python mcp_smoke_client.py "<sql1>" ["<sql2>" ...]
import json, subprocess, sys, time, threading, queue, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(ROOT, ".e2e-local-jwt.txt")

proc = subprocess.Popen(
    ["bash", os.path.join(ROOT, "examples", "mcp-custos.sh")],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    cwd=ROOT, text=True, encoding="utf-8", bufsize=1)

lines: "queue.Queue[str]" = queue.Queue()
threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()

def send(msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()

def recv(want_id, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        try:
            line = lines.get(timeout=1).strip()
        except queue.Empty:
            continue
        if not line.startswith("{"):
            continue                      # 防御：跳过任何非 JSON 噪声
        m = json.loads(line)
        if m.get("id") == want_id:
            return m
    raise TimeoutError(f"no response id={want_id}")

# ── MCP 握手 ──
send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2024-11-05", "capabilities": {},
    "clientInfo": {"name": "claude-mcp-smoke", "version": "1.0"}}})
init = recv(1)
print("[handshake] server =", json.dumps(init["result"].get("serverInfo"), ensure_ascii=False))
send({"jsonrpc": "2.0", "method": "notifications/initialized"})

send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
tools = recv(2)["result"]["tools"]
print("[tools/list]", ", ".join(t["name"] for t in tools))

# ── 等运维侧自举完成（解封 + 令牌注入）──
for _ in range(40):
    if os.path.exists(TOKEN_FILE) and os.path.getsize(TOKEN_FILE) > 50:
        break
    time.sleep(1)
token = open(TOKEN_FILE, encoding="utf-8").read().strip()
print("[token] injected,", len(token), "chars (agent 仅持令牌，无任何 DB 凭证)")

# ── 工具调用 ──
rid = 3
for sql in sys.argv[1:]:
    send({"jsonrpc": "2.0", "id": rid, "method": "tools/call", "params": {
        "name": "query_db", "arguments": {
            "tool": "db/query_orders", "schema": "appdb", "sql": sql, "userToken": token}}})
    r = recv(rid)["result"]
    text = r["content"][0]["text"]
    print(f"[query_db] sql={sql!r}\n  -> isError={r.get('isError')} {text}")
    rid += 1

proc.stdin.close()
proc.terminate()
