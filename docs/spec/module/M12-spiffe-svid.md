---
id: M12
title: SPIFFE 认证 + X.509-SVID
status: not-started
sprint: v0.3
progress: 0
manualProgress: false
desc: "X.509-SVID 载体：SpiffeId + 自建 CA 签发（URI SAN）+ 验证提取 + SpiffeAuthenticator 接入 Authenticator SPI（OBO 已就绪）。BouncyCastle bcpkix，纯单元。spec/plan 已备好。"
docs:
  - { title: "SPIFFE 设计 spec", path: "docs/superpowers/specs/2026-06-10-custos-spiffe-design.md" }
  - { title: "SPIFFE 实现计划", path: "docs/superpowers/plans/2026-06-10-custos-spiffe.md" }
  - { title: "身份层设计（双载体令牌）", path: "docs/design/03-identity-design.md" }
subtasks:
  - { title: "M12-T1 SpiffeId 解析/渲染（TDD）", done: false }
  - { title: "M12-T2 X509SvidIssuer/Verifier（BC，过期/异CA/无SAN 负路径）", done: false }
  - { title: "M12-T3 SpiffeAuthenticator（PEM → Principal）", done: false }
---

# M12 · SPIFFE + X.509-SVID

JWT 载体已有（M03），本模块补 X.509 载体；attestation 插件谱系/mTLS 接线/CA 轮换为非目标。
