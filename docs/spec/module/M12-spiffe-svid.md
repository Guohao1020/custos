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
  - id: M12-S1
    title: "解析与渲染 SPIFFE 风格身份标识"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/SpiffeId.java"
      - "identity/src/test/java/io/custos/identity/SpiffeIdTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-spiffe.md:30-73"
      - "docs/superpowers/specs/2026-06-10-custos-spiffe-design.md#接口契约"
  - id: M12-S2
    title: "签发并校验 X.509 工作负载证书含负路径"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/X509SvidIssuer.java"
      - "identity/src/main/java/io/custos/identity/X509SvidVerifier.java"
      - "identity/src/test/java/io/custos/identity/X509SvidTest.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-spiffe.md:77-220"
      - "docs/superpowers/specs/2026-06-10-custos-spiffe-design.md:16-30"
  - id: M12-S3
    title: "从证书材料认证出请求主体"
    done: true
    code:
      - "identity/src/main/java/io/custos/identity/SpiffeAuthenticator.java"
      - "identity/src/test/java/io/custos/identity/SpiffeAuthenticatorTest.java"
      - "identity/src/main/java/io/custos/identity/Svid.java"
    docs:
      - "docs/superpowers/plans/2026-06-10-custos-spiffe.md:224-295"
      - "docs/superpowers/specs/2026-06-10-custos-spiffe-design.md#架构与数据流"
---

# M12 · SPIFFE + X.509-SVID

双载体令牌齐了：JWT（M03）+ X.509-SVID（本模块）。SVID 可直接喂 DefaultSts 做 OBO 交集。
attestation 插件谱系/mTLS 接线/CA 轮换留后续。
