# Casbin PERM 元模型（国产授权库）

- **标题**：Casbin / jCasbin 的 PERM 元模型（RBAC/ABAC 统一表达）
- **来源 URL**：https://casbin.org/docs/ · https://github.com/casbin/casbin · https://github.com/casbin/jcasbin
- **许可证**：Apache-2.0（国产开源）
- **校订**：2026-06

## 核心要点（中文摘要）

- **PERM = Policy, Effect, Request, Matchers（+Role）**：用一份 `model.conf` 定义授权"形状"，一份 policy（csv/DB）存具体规则。
  - `[request_definition] r = sub, obj, act`（可加 `dom` 域）
  - `[policy_definition] p = sub, obj, act, eft`
  - `[role_definition] g = _, _`（角色继承，可带 domain）
  - `[policy_effect] e = some(where (p.eft == allow))`（也支持 deny-override 等）
  - `[matchers] m = g(r.sub, p.sub) && r.obj == p.obj && r.act == p.act`
- **一套引擎多种模型**：靠 matcher 表达式在 ACL/RBAC/ABAC/RBAC-with-domains 间切换；ABAC 在 matcher 里读对象属性（`r.obj.Owner`）。
- **内置匹配函数**：`keyMatch/keyMatch2`（RESTful 路径）、`globMatch`、`ipMatch`、`regexMatch`——适合表达路径/工具通配。
- **存储与同步**：**Adapter**（file/MySQL/各 DB）持久化 policy；**Watcher**（NATS/Redis/etcd…）多实例策略热同步；有 cached/synced/distributed enforcer。
- **多语言**：含 **jCasbin（Java）**。

## 对 Custos 的影响

- 权限层（`04`）**落地内核首选 jCasbin**：PERM 承载 RBAC+ABAC，`keyMatch` 承载工具/动作级 scope（SEP-835）。
- **自研 Nacos Adapter + Nacos Watcher**：把 Casbin 的 Watcher 抽象缝合到 Nacos 配置热更新 → **秒级权限变更与吊销**（`05`）。
- 国产 + Apache-2.0 + Java 同栈，契合自主可控；可解释性需 Custos 服务壳增强（`04` §4）。
