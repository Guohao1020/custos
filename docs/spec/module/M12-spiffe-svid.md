---
id: M12
title: SPIFFE 认证 + X.509-SVID
status: done
sprint: v0.3
progress: 100
manualProgress: false
desc: "SpiffeId + BC 自建 CA 签发/验证 X.509-SVID（URI SAN）+ SpiffeAuthenticator 接入 Authenticator SPI。identity 18 单测全绿（含过期/异CA/无SAN 负路径）。"
docs:
  - { title: "SPIFFE 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-spiffe-design.md" }
  - { title: "SPIFFE 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-spiffe.md" }
  - { title: "身份层设计（双载体令牌）", path: "docs/design/03-identity-design.md" }
subtasks:
  - { title: "M12-T1 SpiffeId 解析/渲染（TDD）", done: true }
  - { title: "M12-T2 X509SvidIssuer/Verifier（BC，过期/异CA/无SAN 负路径）", done: true }
  - { title: "M12-T3 SpiffeAuthenticator（PEM → Principal）", done: true }
---

# M12 · SPIFFE + X.509-SVID

双载体令牌齐了：JWT（M03）+ X.509-SVID（本模块）。SVID 可直接喂 DefaultSts 做 OBO 交集。
attestation 插件谱系/mTLS 接线/CA 轮换留后续。
