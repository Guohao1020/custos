# -*- coding: utf-8 -*-
"""Build a single self-contained HTML viewer for all Custos design docs.

Reads README + docs/research + docs/design + docs/references markdown files,
embeds them verbatim into <script type="text/markdown"> blocks, and renders
client-side via marked.js (markdown/tables) + mermaid.js (diagrams) from CDN.
Output: custos-design.html at repo root.
"""
import os
import html

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (relative path, dom id, group, sidebar title)
DOCS = [
    ("README.md", "readme", "开始", "项目总览 README"),

    ("docs/design/00-synthesis.md", "d00", "设计文档", "00 · 综合与决策"),
    ("docs/design/01-architecture.md", "d01", "设计文档", "01 · 总体架构"),
    ("docs/design/02-engine-crypto-design.md", "d02", "设计文档", "02 · 引擎威胁模型与密码学 ★"),
    ("docs/design/03-identity-design.md", "d03", "设计文档", "03 · 身份层与 OBO 委托"),
    ("docs/design/04-authz-design.md", "d04", "设计文档", "04 · 策略层 PDP"),
    ("docs/design/05-nacos-integration.md", "d05", "设计文档", "05 · Nacos 控制面"),
    ("docs/design/06-secrets-broker.md", "d06", "设计文档", "06 · 经纪层 secretless"),
    ("docs/design/07-mvp-vertical-slice.md", "d07", "设计文档", "07 · MVP 纵向线"),
    ("docs/design/08-repo-scaffold.md", "d08", "设计文档", "08 · 脚手架与选型"),

    ("docs/research/openbao.md", "r-openbao", "竞品笔记", "OpenBao（引擎内核）"),
    ("docs/research/vault.md", "r-vault", "竞品笔记", "Vault（BSL 参照）"),
    ("docs/research/spire.md", "r-spire", "竞品笔记", "SPIFFE/SPIRE（身份）"),
    ("docs/research/cerbos.md", "r-cerbos", "竞品笔记", "Cerbos（PDP）"),
    ("docs/research/casbin.md", "r-casbin", "竞品笔记", "Casbin（落地内核）"),
    ("docs/research/nacos.md", "r-nacos", "竞品笔记", "Nacos（护城河）"),
    ("docs/research/infisical.md", "r-infisical", "竞品笔记", "Infisical（直接竞品）"),
    ("docs/research/jimmer.md", "r-jimmer", "竞品笔记", "Jimmer（持久化 ORM 选型）"),

    ("docs/references/mcp-sep-835.md", "ref-mcp", "引用资料", "MCP / SEP-835"),
    ("docs/references/oauth2-token-exchange-obo.md", "ref-oauth", "引用资料", "OAuth2 Token-Exchange / OBO"),
    ("docs/references/spiffe-spire.md", "ref-spiffe", "引用资料", "SPIFFE / SPIRE"),
    ("docs/references/vault-openbao-barrier-seal-lease.md", "ref-vault", "引用资料", "Vault/OpenBao Barrier·Seal·Lease"),
    ("docs/references/cerbos-policy-model.md", "ref-cerbos", "引用资料", "Cerbos 策略模型"),
    ("docs/references/casbin-perm-model.md", "ref-casbin", "引用资料", "Casbin PERM 模型"),
    ("docs/references/nacos-mcp-registry-config.md", "ref-nacos", "引用资料", "Nacos MCP / 配置热更新"),
    ("docs/references/gm-crypto-sm2-sm3-sm4.md", "ref-gm", "引用资料", "国密 SM2/SM3/SM4"),

    ("docs/superpowers/specs/2026-06-09-custos-overall-architecture-spec.md", "spec-overall", "规格", "整体架构 spec"),
    ("docs/superpowers/specs/2026-06-09-custos-mvp-v0.1-design.md", "spec-mvp", "规格", "MVP v0.1 接口契约 spec"),

    ("docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-foundation.md", "plan-1", "实现计划", "1/5 引擎密码学基座"),
    ("docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-engine-persistence.md", "plan-2", "实现计划", "2/5 引擎持久化(Jimmer)"),
    ("docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-identity-jwt.md", "plan-3", "实现计划", "3/5 身份 JWT"),
    ("docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-authz-nacos.md", "plan-4", "实现计划", "4/5 策略+Nacos 秒级吊销"),
    ("docs/superpowers/plans/2026-06-09-custos-mvp-v0.1-broker-demo.md", "plan-5", "实现计划", "5/5 经纪+MCP+demo"),
]

GROUP_ORDER = ["开始", "设计文档", "竞品笔记", "引用资料", "规格", "实现计划"]


def read(rel):
    with open(os.path.join(ROOT, rel), "r", encoding="utf-8") as f:
        return f.read()


def main():
    script_blocks = []
    toc_groups = {g: [] for g in GROUP_ORDER}
    for rel, did, group, title in DOCS:
        md = read(rel)
        # guard against breaking out of the <script> block
        md = md.replace("</script>", "<\\/script>")
        script_blocks.append(
            f'<script type="text/markdown" id="md-{did}">\n{md}\n</script>'
        )
        toc_groups[group].append((did, title))

    toc_html_parts = []
    for g in GROUP_ORDER:
        items = toc_groups[g]
        if not items:
            continue
        toc_html_parts.append(f'<div class="toc-group"><div class="toc-group-title">{html.escape(g)}</div>')
        for did, title in items:
            toc_html_parts.append(
                f'<button class="toc-item" data-target="{did}">{html.escape(title)}</button>'
            )
        toc_html_parts.append("</div>")
    toc_html = "\n".join(toc_html_parts)
    scripts_html = "\n".join(script_blocks)

    page = TEMPLATE.replace("/*__TOC__*/", toc_html).replace("/*__DOCS__*/", scripts_html)
    out = os.path.join(ROOT, "custos-design.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(page)
    size = os.path.getsize(out)
    print(f"written: {out} ({size/1024:.1f} KB, {len(DOCS)} docs)")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Custos · 设计文档库</title>
<style>
  :root{
    --bg:#0e1116; --panel:#161b23; --panel2:#1c2530; --line:#27313d;
    --txt:#e6edf3; --muted:#9aa7b4; --dim:#6b7682;
    --brand:#3b82f6; --brand2:#22d3ee; --accent:#a855f7; --ok:#22c55e; --warn:#f59e0b; --gold:#fbbf24;
    --code:#0b0e13; --sidebar:300px;
  }
  *{box-sizing:border-box}
  html{scroll-behavior:smooth}
  body{margin:0;background:var(--bg);color:var(--txt);
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
    line-height:1.7;font-size:15px;-webkit-font-smoothing:antialiased}
  a{color:var(--brand2);text-decoration:none} a:hover{text-decoration:underline}

  /* layout */
  .sidebar{position:fixed;top:0;left:0;width:var(--sidebar);height:100vh;overflow-y:auto;
    background:linear-gradient(180deg,#10141b,#0e1116);border-right:1px solid var(--line);padding:18px 14px 40px}
  .main{margin-left:var(--sidebar);padding:0}
  .content{max-width:920px;margin:0 auto;padding:38px 34px 120px}

  /* sidebar brand */
  .brand{display:flex;align-items:center;gap:10px;padding:6px 8px 14px;border-bottom:1px solid var(--line);margin-bottom:12px}
  .brand .logo{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center;
    background:linear-gradient(140deg,#1d4ed8,#a855f7);font-weight:800;color:#fff;font-size:16px}
  .brand b{font-size:15px} .brand .tag{font-size:11px;color:var(--dim)}
  .filter{width:100%;margin:8px 0 14px;padding:8px 11px;border-radius:8px;border:1px solid var(--line);
    background:var(--panel);color:var(--txt);font-size:13px}
  .filter::placeholder{color:var(--dim)}

  .toc-group{margin-bottom:14px}
  .toc-group-title{font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:var(--accent);
    font-weight:700;padding:4px 8px;margin-bottom:4px}
  .toc-item{display:block;width:100%;text-align:left;background:none;border:none;color:var(--muted);
    padding:7px 10px;border-radius:7px;cursor:pointer;font-size:13.3px;line-height:1.4;font-family:inherit}
  .toc-item:hover{background:var(--panel);color:var(--txt)}
  .toc-item.active{background:rgba(59,130,246,.16);color:#fff;border-left:3px solid var(--brand);padding-left:7px}

  /* content typography */
  .content h1{font-size:30px;line-height:1.2;margin:6px 0 18px;letter-spacing:-.4px;padding-bottom:12px;border-bottom:1px solid var(--line)}
  .content h2{font-size:22px;margin:34px 0 12px;letter-spacing:-.2px}
  .content h3{font-size:17.5px;margin:24px 0 8px;color:#dbe4ee}
  .content h4{font-size:15px;margin:18px 0 6px;color:var(--muted)}
  .content p{margin:11px 0;color:#cdd6df}
  .content strong{color:#fff;font-weight:600}
  .content ul,.content ol{padding-left:24px;margin:10px 0} .content li{margin:5px 0;color:#cdd6df}
  .content blockquote{border-left:3px solid var(--brand);background:var(--panel);margin:16px 0;
    padding:10px 18px;border-radius:0 10px 10px 0;color:var(--muted)}
  .content blockquote strong{color:var(--gold)}
  .content hr{border:none;border-top:1px solid var(--line);margin:28px 0}
  .content code{background:#11161d;border:1px solid var(--line);border-radius:5px;padding:1px 6px;font-size:13px;
    font-family:"SF Mono",ui-monospace,Consolas,monospace;color:#7ee787}
  .content pre{background:var(--code);border:1px solid var(--line);border-radius:12px;padding:15px 17px;overflow:auto;margin:14px 0}
  .content pre code{background:none;border:none;padding:0;color:#c9d4e0;font-size:12.8px;line-height:1.55}

  /* tables */
  .content table{width:100%;border-collapse:collapse;margin:16px 0;font-size:13.3px;display:block;overflow-x:auto}
  .content th,.content td{text-align:left;padding:9px 12px;border:1px solid var(--line);vertical-align:top}
  .content th{background:var(--panel2);color:#dbe4ee;font-weight:600;white-space:nowrap}
  .content tr:nth-child(even) td{background:rgba(255,255,255,.015)}

  /* mermaid */
  .mermaid{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px;margin:18px 0;text-align:center}
  .mermaid svg{max-width:100%;height:auto}

  /* footer nav */
  .docnav{display:flex;justify-content:space-between;margin-top:40px;padding-top:18px;border-top:1px solid var(--line);gap:12px}
  .docnav button{background:var(--panel);border:1px solid var(--line);color:var(--muted);padding:10px 16px;
    border-radius:9px;cursor:pointer;font-size:13px;font-family:inherit;max-width:48%}
  .docnav button:hover{color:#fff;border-color:#3f4d5d} .docnav button:disabled{opacity:.3;cursor:default}
  .cdn-warn{display:none;background:rgba(245,158,11,.12);border:1px solid var(--warn);color:#fcd34d;
    padding:10px 14px;border-radius:9px;margin-bottom:16px;font-size:13px}

  .menu-btn{display:none}
  @media(max-width:980px){
    .sidebar{transform:translateX(-100%);transition:.2s;z-index:50}
    .sidebar.open{transform:none}
    .main{margin-left:0}
    .menu-btn{display:flex;position:fixed;top:12px;left:12px;z-index:60;width:42px;height:42px;border-radius:10px;
      align-items:center;justify-content:center;background:var(--panel);border:1px solid var(--line);color:#fff;font-size:20px;cursor:pointer}
    .content{padding:64px 18px 100px}
  }
</style>
</head>
<body>
<button class="menu-btn" onclick="document.querySelector('.sidebar').classList.toggle('open')">☰</button>
<nav class="sidebar">
  <div class="brand">
    <div class="logo">C</div>
    <div><b>Custos</b><div class="tag">设计文档库 · v2 草案</div></div>
  </div>
  <input class="filter" id="filter" placeholder="筛选文档…">
  <div id="toc">
/*__TOC__*/
  </div>
</nav>
<main class="main">
  <div class="content">
    <div class="cdn-warn" id="cdnWarn">⚠️ 未能加载在线渲染库（marked/mermaid CDN）。已显示原始 Markdown；联网后刷新可获得完整渲染。</div>
    <div id="content"></div>
    <div class="docnav">
      <button id="prevBtn">← 上一篇</button>
      <button id="nextBtn">下一篇 →</button>
    </div>
  </div>
</main>

/*__DOCS__*/

<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
<script>
(function(){
  var order = Array.prototype.map.call(document.querySelectorAll('.toc-item'), function(b){return b.dataset.target;});
  var hasMarked = typeof window.marked !== 'undefined';
  var hasMermaid = typeof window.mermaid !== 'undefined';
  if(!hasMarked || !hasMermaid){ document.getElementById('cdnWarn').style.display='block'; }
  if(hasMarked){ marked.setOptions({gfm:true, breaks:false}); }
  if(hasMermaid){ mermaid.initialize({startOnLoad:false, theme:'dark', securityLevel:'loose', themeVariables:{fontSize:'13px'}}); }

  function rawMd(id){ var el=document.getElementById('md-'+id); return el? el.textContent : '(missing)'; }

  function renderMermaid(){
    if(!hasMermaid) return;
    var blocks = document.querySelectorAll('#content code.language-mermaid');
    var nodes = [];
    blocks.forEach(function(code){
      var div = document.createElement('div');
      div.className='mermaid';
      div.textContent = code.textContent;
      var pre = code.closest('pre');
      pre.parentNode.replaceChild(div, pre);
      nodes.push(div);
    });
    if(nodes.length){ try{ mermaid.run({nodes:nodes}); }catch(e){ console.warn(e); } }
  }

  function show(id){
    var md = rawMd(id);
    var c = document.getElementById('content');
    if(hasMarked){ c.innerHTML = marked.parse(md); renderMermaid(); }
    else { var pre=document.createElement('pre'); pre.textContent=md; c.innerHTML=''; c.appendChild(pre); }
    document.querySelectorAll('.toc-item').forEach(function(b){ b.classList.toggle('active', b.dataset.target===id); });
    var idx = order.indexOf(id);
    var prev=document.getElementById('prevBtn'), next=document.getElementById('nextBtn');
    prev.disabled = idx<=0; next.disabled = idx>=order.length-1;
    prev.onclick=function(){ if(idx>0) go(order[idx-1]); };
    next.onclick=function(){ if(idx<order.length-1) go(order[idx+1]); };
    window.scrollTo(0,0);
    if(window.innerWidth<=980){ document.querySelector('.sidebar').classList.remove('open'); }
    if(history.replaceState) history.replaceState(null,'','#'+id);
  }
  function go(id){ show(id); }

  document.querySelectorAll('.toc-item').forEach(function(b){
    b.addEventListener('click', function(){ go(b.dataset.target); });
  });
  document.getElementById('filter').addEventListener('input', function(e){
    var q=e.target.value.trim().toLowerCase();
    document.querySelectorAll('.toc-item').forEach(function(b){
      b.style.display = (!q || b.textContent.toLowerCase().indexOf(q)>=0) ? 'block':'none';
    });
    document.querySelectorAll('.toc-group').forEach(function(g){
      var any=Array.prototype.some.call(g.querySelectorAll('.toc-item'), function(b){return b.style.display!=='none';});
      g.style.display = any?'block':'none';
    });
  });

  var initial = (location.hash||'').replace('#','');
  show(order.indexOf(initial)>=0 ? initial : order[0]);
})();
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
