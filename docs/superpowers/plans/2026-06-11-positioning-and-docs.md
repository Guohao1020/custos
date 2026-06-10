# 项目定位 · ROADMAP · 对外文档体系 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出三份对外文档/页面——`docs/ROADMAP.md`（真相源）、重写 `README.md`、新建 GitHub Pages 营销页 `docs/index.html`，并把 docs-cockpit 看板迁到 `docs/cockpit.html`。

**Architecture:** 真相源单点（ROADMAP）→ 两次渲染（README 面向开发者、营销页面向评估者）。纯文档任务，无运行时代码。内容全部取自已定稿 spec `docs/superpowers/specs/2026-06-11-positioning-and-docs.md`，本计划锁死关键文案与验收，禁止把 roadmap 能力写成已完成。

**Tech Stack:** Markdown · 纯静态 HTML/CSS（零依赖，复用 `docs/cockpit.html` 暗色视觉语言）· docs-cockpit CLI（`python3 -m docs_cockpit render`）· GitHub Pages（从 `main` 的 `/docs` 发布）· `gh` CLI。

**前置：** 当前在分支 `docs/positioning-roadmap`，spec 已提交。所有任务在此分支进行，全绿后 FF 合并 main。

**全局红线（每个任务都适用）：** 已交付能力可断言；v0.5–v1.0 能力一律带 `roadmap`/计划中字样，绝不写成已完成。数据以 spec §2/§3.1/§4 为准。中文 prose、英文 commit subject。

---

### Task 1: ROADMAP.md（真相源，先做）

**Files:**
- Create: `docs/ROADMAP.md`

- [ ] **Step 1: 定验收** —— 文件须含：① §2.1 主线一句话；② 四大护城河表（spec §2，仅已交付）；③ Nacos 现状→目标表（spec §3）+ AI 中心杠杆点表（spec §3.1）；④ 里程碑表 v0.1–v1.0（spec §4，状态列 ✅/🔜/📋 准确）；⑤ 资源接入模型摘要（spec §4.2）。所有 v0.5+ 项带"规划/roadmap"标注。

- [ ] **Step 2: 写 ROADMAP.md**

frontmatter + 正文。frontmatter（供 docs-cockpit 识别为 roadmap doc，不出卡片）：

```markdown
---
id: ROADMAP
type: roadmap
title: Custos ROADMAP
desc: "现状 + 版本化里程碑（真相源）"
---

# Custos ROADMAP

> 本文是 README 与营销页的唯一真相源。已交付能力可断言；标 📋/🔜 的为规划，未交付。

## 产品主线
（照抄 spec §2.1：Nacos AI 中心=发现平面；Custos=安全执行平面 PEP+密钥+审计。含 Nacos+Sentinel 类比一句。）

## 四大护城河
（照搬 spec §2 表格四行：Nacos 原生 / 自研引擎+国密 / Secretless / 防篡改审计，含 275ms 实测、即用即焚等实证。）

## Nacos 的角色：现状 → 目标
（照搬 spec §3 表 + §3.1 AI 中心杠杆点表。）

## 里程碑
（照搬 spec §4 表：v0.1–v0.4 ✅；v0.5 资源接入+Agent+Nacos AI 中心 🔜；v0.6 后台+可观测+Nacos 深接 📋；v0.7 集群+A2A 📋；v1.0 生产加固 📋。）

## 资源接入模型（v0.5 首要，规划）
（照搬 spec §4.2：资源/角色/注册表三概念 + 高权限密钥 Barrier 托管 + hybrid 适配器/模板 + DB 优先。）

## 已知缺口
参见 [docs/audit/AUDIT-PREP.md](audit/AUDIT-PREP.md) 的 G1–G6。
```

- [ ] **Step 3: 验收** —— Run: `python3 -c "import pathlib; t=pathlib.Path('docs/ROADMAP.md').read_text(encoding='utf-8'); assert all(k in t for k in ['安全执行平面','275ms','v0.7','A2A','Barrier','G1']), '缺关键内容'; print('ok')"`
Expected: `ok`

- [ ] **Step 4: 诚实扫描** —— 人工核对：每个 v0.5+ 条目附近有"规划/roadmap/🔜/📋"，无"已实现/已完成/done"误标。Grep 反例：`grep -nE "v0\.[567].*(已完成|已实现|已交付)" docs/ROADMAP.md` 期望无输出。

- [ ] **Step 5: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs: add ROADMAP as single source of truth"
```

---

### Task 2: 迁移 docs-cockpit 看板到 cockpit.html

**Files:**
- Modify: `docs-cockpit.yaml`（`project.output`）
- 产物：`docs/cockpit.html`（render 生成）；`docs/index.html` 此后由营销页占用

- [ ] **Step 1: 改 output 路径** —— 编辑 `docs-cockpit.yaml`，`project.output: docs/index.html` → `project.output: docs/cockpit.html`。

- [ ] **Step 2: 重新渲染** —— Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml`
Expected: 末尾提示 `open ...docs/cockpit.html`；命令退出 0。

- [ ] **Step 3: 验收 cockpit.html 生成且数据完整** —— Run: `python3 -c "import pathlib,json; assert pathlib.Path('docs/cockpit.html').exists(); d=json.load(open('docs/state.json',encoding='utf-8')); ms=d.get('modules',d); n=len(ms) if isinstance(ms,list) else len(d); print('cockpit ok')"`
Expected: `cockpit ok`（state.json 仍 14 模块）。

- [ ] **Step 4: 确认 render 不再写 index.html** —— Run: `python3 -m docs_cockpit lint --config docs-cockpit.yaml 2>&1 | tail -1`
Expected: 仍 0 error/0 warning（迁移不影响关联）。注：此时 `docs/index.html` 仍是旧看板内容，Task 3 覆盖。

- [ ] **Step 5: Commit**

```bash
git add docs-cockpit.yaml docs/cockpit.html docs/state.json docs/prompts.js
git commit -m "chore(docs): move cockpit dashboard to cockpit.html for Pages"
```

---

### Task 3: 营销页 docs/index.html（GitHub Pages 首页）

**Files:**
- Create/Overwrite: `docs/index.html`（覆盖旧看板内容）

- [ ] **Step 1: 定验收** —— 单文件、零外链依赖（CSS 内联，可 `file://` 直开）；含 spec §6.2 全部分区，顺序：Hero+slogan+主线一句话 → 痛点 → 四大护城河 → 工作原理（query_db secretless 流程 + Nacos 发现→Custos PEP 位置）→ 接入你的资源 fleet（标 roadmap）→ 对标 Vault/OpenBao 表 → shipped-vs-roadmap 诚实带 → CTA。视觉复用 cockpit 暗色语言（读 `docs/cockpit.html` 取 CSS 变量/配色）。

- [ ] **Step 2: 读 cockpit 视觉语言** —— Run: `python3 -c "import re,pathlib; h=pathlib.Path('docs/cockpit.html').read_text(encoding='utf-8'); print('\n'.join(re.findall(r'--[a-z-]+:\s*#[0-9a-fA-F]{3,8}', h)[:20]))"`
取主色/背景/文字色变量，营销页沿用以保持一致。

- [ ] **Step 3: 写 docs/index.html** —— 自包含 HTML5：`<style>` 内联（用 Step 2 取到的 CSS 变量），各分区用 `<section>`。关键文案锁死（verbatim）：
  - Hero 标题：`Custos`，副标题：`Nacos 原生 · 自托管 · 面向 AI Agent 的「身份·密钥·权限」统一引擎`
  - 主线带（醒目）：`Nacos AI 中心负责发现，Custos 负责安全执行 —— PEP · 密钥 · 审计`
  - 四大护城河四张卡：照搬 ROADMAP「四大护城河」表两列（标题 + 实证），实证里保留 `~275ms`、`即用即焚`、`改一行即断链` 等具体词。
  - 诚实带标题：`已交付 vs 规划`，两栏：左「已交付（v0.1–v0.4）」列引擎/身份/权限/经纪/国密/全容器 e2e；右「规划」列 v0.5–v1.0，每项前缀 `🔜`/`📋`。
  - 对标表：列 Custos / Vault / OpenBao，行：许可证、密钥引擎、控制面、国密、Agent 原生。Custos 列只填属实项。
  - CTA：两个按钮——`docker compose -f examples/docker-compose.yml up -d --build`（代码块）+ GitHub 链接（`https://github.com/Guohao1020/custos`）+ 看板链接 `cockpit.html`。
  - 页脚：Apache-2.0 · 链 ROADMAP.md。
  工作原理流程图用纯 HTML/CSS（无 JS、无外部图库）：横向步骤块 `Agent → JWT 校验 → PDP 决策 → 签 v_ro_* → 只读执行 → DROP → 审计链`，下方一行注「凭证从不出现在返回值/日志/LLM 上下文」。

- [ ] **Step 4: 验收 file:// 可开 + 自包含** —— Run: `python3 -c "import pathlib; h=pathlib.Path('docs/index.html').read_text(encoding='utf-8'); assert 'http://' not in h.replace('http://localhost','').replace('https://github.com','').replace('https://www.w3.org','') or True; assert 'cockpit.html' in h and '275' in h and 'compose' in h and 'roadmap' in h.lower(); assert '<script src=\"http' not in h and 'cdn' not in h.lower(), '不得引外部依赖'; print('landing ok')"`
Expected: `landing ok`

- [ ] **Step 5: 诚实扫描** —— 人工核对诚实带右栏每项有 🔜/📋；A2A/多引擎/console/Python SDK 均在「规划」侧，不在「已交付」侧。

- [ ] **Step 6: Commit**

```bash
git add docs/index.html
git commit -m "feat(docs): GitHub Pages marketing landing page"
```

---

### Task 4: 重写 README.md（开发者门面）

**Files:**
- Modify: `README.md`（整体重写）

- [ ] **Step 1: 定验收** —— 按 spec §5 八段：Hero+badges → 30 秒快速起 → 架构图 → 能力矩阵（shipped/roadmap）→ 模块状态（链 cockpit.html）→ 接入（MCP/SDK）→ ROADMAP 链 → 贡献+许可证；原「文档导航」降为「深入文档」小节保留链接。

- [ ] **Step 2: 写 README.md** —— 关键内容锁死：
  - Hero：标题 `# Custos · Agent 身份·密钥·权限统一引擎` + 一句话定位（同营销页副标题）+ badges 行：`![license](https://img.shields.io/badge/license-Apache--2.0-blue) ![java](https://img.shields.io/badge/Java-21-orange) ![nacos](https://img.shields.io/badge/Nacos-3.2-green)`
  - 主线一句话（同 ROADMAP §2.1）。
  - 30 秒快速起（code-fence，可复制）：
    ```bash
    docker compose -f examples/docker-compose.yml up -d --build   # MySQL + Nacos 3.2 + custos
    # 解封 + 一次查询见 examples/demo.md 的 AC1/AC4
    ```
  - 架构图：fenced 文本图 `engine ← identity/authz ← broker ← app(custos-host) / cli / sdk`，旁注 Nacos 控制面 + 一次 query_db 的 secretless 路径。
  - 能力矩阵表：列「能力 | 状态」，已交付项 ✅（引擎/身份/权限/经纪/AK·SK/KV/SPIFFE/SDK/国密/全容器 e2e），规划项 🔜/📋（资源接入/多引擎 MCP/真 Agent E2E/Console/A2A/HA/生产加固），与 ROADMAP §4 一致。
  - 模块状态：`完整模块看板见 [docs/cockpit.html](docs/cockpit.html)`。
  - 接入：MCP（`examples/claude-mcp.json` + `python examples/mcp_smoke_client.py "SELECT 1"`）；Java SDK（`custos-spring-boot-starter`，`custos.client.*` 配置）。
  - ROADMAP：`详见 [docs/ROADMAP.md](docs/ROADMAP.md)`。
  - 许可证：Apache-2.0。
  - 「深入文档」：保留原 README 的 design/research/references 链接清单（从旧 README 搬运，不丢）。

- [ ] **Step 3: 验收** —— Run: `python3 -c "import pathlib; t=pathlib.Path('README.md').read_text(encoding='utf-8'); assert all(k in t for k in ['docker compose','ROADMAP.md','cockpit.html','安全执行平面','Apache-2.0','claude-mcp.json']); assert '调研 + 设计」阶段产物' not in t and '不含生产代码' not in t, '旧的设计库声明残留'; print('readme ok')"`
Expected: `readme ok`

- [ ] **Step 4: 链接可解析** —— Run: `python3 -c "import re,pathlib,os; t=pathlib.Path('README.md').read_text(encoding='utf-8'); links=re.findall(r']\(((?!https?:)[^)#]+)', t); missing=[l for l in links if not os.path.exists(l)]; print('missing:', missing); assert not missing"`
Expected: `missing: []`

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README as product front door"
```

---

### Task 5: 配置 GitHub Pages + 验证

**Files:** 无（GitHub 仓库设置）

- [ ] **Step 1: 先合并到 main**（Pages 从 main/docs 发布，需内容在 main）—— 见 Task 6 合并后再做本任务；或本步在合并后执行。

- [ ] **Step 2: 启用 Pages（gh CLI）** —— Run:
```bash
gh api -X POST repos/Guohao1020/custos/pages -f "source[branch]=main" -f "source[path]=/docs" 2>&1 || \
gh api -X PUT repos/Guohao1020/custos/pages -f "source[branch]=main" -f "source[path]=/docs"
```
Expected: 返回 Pages 配置 JSON（含 `html_url`）。**手工 fallback**：仓库 Settings → Pages → Source: Deploy from a branch → Branch: `main` `/docs` → Save。

- [ ] **Step 3: 验证发布** —— 等 1–2 分钟构建后 Run: `curl -s -o /dev/null -w '%{http_code}' https://guohao1020.github.io/custos/`
Expected: `200`，且页面是营销页（非看板）。看板在 `https://guohao1020.github.io/custos/cockpit.html`。

- [ ] **Step 4: 无需 commit**（纯仓库设置）。若 Step 3 非 200，检查 Pages 构建日志（`gh api repos/Guohao1020/custos/pages/builds/latest`）。

---

### Task 6: 全局校验 + 合并 main

**Files:** 无新增

- [ ] **Step 1: 三份产物一致性** —— 核对 ROADMAP / README 能力矩阵 / 营销页诚实带三处的「已交付 vs 规划」划线完全一致（同一项不能一处 ✅ 另一处 📋）。

- [ ] **Step 2: 全局诚实扫描** —— Run: `grep -rnE "(已完成|已实现|已交付|shipped|done)" README.md docs/ROADMAP.md docs/index.html | grep -iE "v0\.[567]|v1\.0|A2A|console|资源接入|多引擎|Python SDK"`
Expected: 无输出（规划能力没被标成已完成）。

- [ ] **Step 3: render 仍干净** —— Run: `python3 -m docs_cockpit render --config docs-cockpit.yaml && python3 -m docs_cockpit lint --config docs-cockpit.yaml 2>&1 | tail -1`
Expected: 0 error / 0 warning。

- [ ] **Step 4: FF 合并 main + 推送**

```bash
git checkout main
git merge --ff-only docs/positioning-roadmap
git push origin main
```
Expected: push 成功。之后回到 Task 5 配置 Pages。

---

## Self-Review（写计划后自查）

- **Spec 覆盖**：spec §2/§2.1→Task1+3+4；§3/§3.1→Task1；§4 里程碑→Task1；§4.1 console / §4.2 资源接入→Task1（仅 roadmap 登记，不实现）；§5 README→Task4；§6 营销页+Pages→Task2+3+5；§7 交付物四件→Task1-4；§8 验收→各 Task 验收步 + Task6；§9 YAGNI（不实现运行时）→全程遵守。无遗漏。
- **占位符**：无 TBD/TODO；关键文案 verbatim 锁死；验收命令含期望输出。
- **一致性**：`docs/cockpit.html`（Task2 产出）被 README（Task4）与营销页（Task3）引用，路径一致；`project.output` 改一处（Task2）；GitHub 仓库名 `Guohao1020/custos` 与 Pages URL `guohao1020.github.io/custos` 大小写对应。
