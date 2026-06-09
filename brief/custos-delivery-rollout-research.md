# Custos · 灰度发布与 K8s CICD 集成调研（轻量）

> 目的：为 Custos 第 4 条横切能力「**交付与灰度（Delivery & Rollout）**」划定边界——哪些自研、哪些复用集成，避免把项目撑成又一个发布系统。
>
> 校订于 2026-06。结论是建议，落地前以官方文档与 PoC 为准。

---

## 1. 一句话结论

Custos **不做** CICD 引擎和流量调度器（这些赛道成熟且拥挤），只做三件**和 AI 治理真正交叉**的事，其余全部复用对接：

1. **AI 资产灰度**（Prompt / 模型 / 工具的版本化金丝雀）→ **自研**（薄层，基于 Nacos 灰度 + Higress 流量切分）。
2. **能力 GitOps**（工具/模型/Prompt 声明式定义在 Git，同步到 Custos/Nacos）→ **自研 schema + reconciler，借 Argo CD/Flux 驱动**。
3. **CICD 操作即受治理工具**（agent 触发 deploy/rollback/canary，经审批+审计+短时凭证）→ **自研封装**，底层调 Argo Rollouts/Argo CD。
4. **普通微服务灰度 + 流水线本身** → **复用** Argo Rollouts / OpenKruise / Higress / Argo CD / Flux / Tekton，Custos 只当治理入口。

---

## 2. 候选工具分层速览

### 2.1 渐进式交付 / 灰度（流量与工作负载层）

| 工具 | 定位 | 机制 | 与 Custos 关系 |
|------|------|------|----------------|
| **Argo Rollouts** | K8s 渐进式交付控制器 | 用 Rollout CRD **替换** Deployment，支持金丝雀/蓝绿/分析/实验 | 微服务灰度首选，Custos 复用并治理 |
| **Flagger** | 渐进式交付 operator | **不替换** Deployment，配合 service mesh（Istio/Linkerd）或 Ingress 权重做金丝雀 | 备选，已用 mesh 时顺手 |
| **OpenKruise Rollouts** | 增强工作负载 + 旁路灰度 | 旁路式渐进发布，集成 Ingress（NGINX/ALB/**Higress**） | 与 Higress 同生态，灰度可对接 |
| **Higress / Istio 流量切分** | 网关/网格流量权重 | 按权重/标签分流 | **Custos 直接用它做 AI 资产灰度的流量面** |
| **Nacos 配置灰度（Beta）** | 配置按 IP 灰度 | 先对指定实例推送新配置 | **Custos 做 Prompt/模型配置灰度的底座** |

### 2.2 GitOps 持续交付（声明式同步层）

| 工具 | 定位 | 特点 | 与 Custos 关系 |
|------|------|------|----------------|
| **Argo CD** | GitOps CD，强 UI、多集群 | 维护资源图谱、可视化；v3.1+ 可引用 OCI registry 制品 | 驱动「能力 GitOps」首选 |
| **Flux CD** | 轻量、K8s 原生 | 控制器解耦、内存占用低、扩展性随 API server | 轻量场景或已用 Flux 时 |
| **Tekton / GH Actions / Jenkins** | CI 流水线引擎 | 构建/测试/打包 | Custos **不碰**，仅作为外部触发方对接 |

> 行业背景：2026 年 GitOps 已是 60%+ 企业的主要交付机制；MLOps 已出现「YAML 声明模型版本 + Git 提交回滚」的实践——这正是「能力 GitOps」的可借鉴范式。

---

## 3. 边界裁决：自研 vs 复用

| 能力 | 归属 | Custos 做什么 | 复用谁 |
|------|------|---------------|--------|
| **AI 资产灰度**（Prompt/模型/工具版本金丝雀） | 🟢 核心自研 | 版本模型、灰度策略、按 namespace/标签分流、一键回滚/熔断 | Nacos 灰度 + Higress 流量切分 |
| **能力 GitOps**（声明式注册） | 🟢 核心自研 | 定义 CRD/YAML schema、reconciler，把 Git 中的工具/模型/Prompt 同步到 Custos/Nacos，带环境晋级与审批 | Argo CD / Flux 驱动同步 |
| **CICD 操作即治理工具** | 🟢 核心自研 | 把 deploy/rollback/canary 封装为 MCP 工具，套审批 + 审计 + 短时凭证 | 底层调 Argo Rollouts/Argo CD API |
| **微服务灰度**（工作负载/流量调度） | 🟡 复用集成 | 仅做治理入口与可观测聚合 | Argo Rollouts / OpenKruise / Higress |
| **CI 流水线引擎** | 🔴 不做 | 仅作为外部触发方对接 | Tekton / GH Actions / Jenkins |
| **GitOps 同步引擎** | 🔴 不做 | 仅提供 Custos 资源的 reconciler/插件 | Argo CD / Flux |

红线判据：**「是治理 AI 能力/凭证」→ 进核心；「是通用交付/流量/流水线」→ 复用对接。**

---

## 4. 为什么这样不散（一致性逻辑）

Custos 的统一主题是：**把「AI 能力 + 它们的交付」都当成可治理、可版本、可审计、可灰度、可安全访问的资产。**

- 灰度以「**AI 资产版本金丝雀**」的口径进来 → 自洽（它本就是治理面的一部分）。
- CICD 以「**发布操作被治理 + CI 也需要短时凭证**」的口径进来 → 自洽（复用密钥支柱）。
- 一旦以「我也要做发布系统/流水线」的口径进来 → 跑偏，坚决不做。

两个天然的复用接点强化了这套逻辑：① Custos 的密钥经纪可顺带给 **CI runner 发短时凭证**；② CICD 操作天然适合做成**受审批的 agent 工具**。

---

## 5. 建议的第 4 横切能力定义（写进 PRD/白皮书）

> **横切能力 · 交付与灰度（Delivery & Rollout）**
> 范围严格限定为：(a) AI 资产灰度发布（Prompt/模型/工具的版本化金丝雀与回滚）；(b) 能力 GitOps（声明式定义 + 环境晋级 + 审批，借 Argo CD/Flux 同步）；(c) CICD 操作即受治理工具（deploy/rollback/canary 经审批+审计+短时凭证）。
> 明确非目标：不自研 CI 引擎、不自研流量调度/渐进交付控制器、不自研 GitOps 同步引擎——一律复用 Argo Rollouts / OpenKruise / Higress / Argo CD / Flux / Tekton，Custos 仅作治理入口与编排。

### 风险与提醒

- **范围蔓延**：这是最大风险。每加一个"顺手也做"的发布功能都要回到红线判据检验。
- **集成面增多**：对接 Argo/Flux 会增加测试矩阵，建议 v0.1 先只接 1 个（Argo 系），其余留接口。
- **版本耦合**：Argo CD v3.1+ 的 OCI 能力、OpenKruise×Higress 集成等都在演进，锁定验证过的版本组合。

---

## 6. 对 MVP 的影响

之前建议的 v0.1 纵向线（零改造上架工具 → Claude 调用 → 短时 secretless DB 访问）**先不含**这条横切能力。建议把「交付与灰度」放到 **v0.2**，且首版只做最有差异化的一小块：**Prompt/模型的灰度发布 + 一键回滚**（纯基于 Nacos 灰度 + Higress，不引入 Argo），验证价值后再接 GitOps 与 CICD-as-tools。

---

## 7. 参考资料（校订于 2026-06）

- [Argo Rollouts](https://argoproj.github.io/rollouts/) · [Canary 文档](https://argo-rollouts.readthedocs.io/en/stable/features/canary/)
- [OpenKruise Rollouts](https://openkruise.io/rollouts/introduction)（集成 Higress）
- [Argo Rollouts vs Flagger（2026）](https://oneuptime.com/blog/post/2026-03-13-flagger-vs-argo-rollouts-comparison/view)
- [Argo CD vs Flux CD（2026）](https://dev.to/mechcloud_academy/the-gitops-standard-in-2026-a-comparative-research-analysis-of-argocd-and-fluxcd-46d8)
- [GitOps Your Models with ArgoCD](https://dev.to/myroslavmokhammadabd/revolutionize-mlops-gitops-your-models-with-argocd-4hoo)

> 评估为基于公开资料的相对判断；集成前对 Argo/Flux/OpenKruise 与 Higress/Nacos 的版本组合做 PoC。
