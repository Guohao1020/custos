# Custos SPIFFE 认证 + X.509-SVID 设计规格（M12）

> **类型**：路线图子项目 **M12 / P-SPIFFE**（v0.3）设计。SPIFFE 身份的 X.509 载体 + 可插拔认证。
> **校订**：2026-06-10 · **状态**：评审中 · **许可**：Apache-2.0
> **配套**：`docs/design/03-identity-design.md` §2.2（双载体令牌）/§3；生产架构 spec §3/§7。前置：M07 OBO（Authenticator SPI 已存在）。

---

## 1. 目标与范围

给身份层加 **X.509-SVID 载体**：签发携带 SPIFFE ID（URI SAN）的短时 X.509 证书、验证并提取身份、以 `Authenticator` SPI 接入 OBO/STS 链路。

- **纳入**：`SpiffeId`（解析/渲染 `spiffe://<trust-domain>/<path>`）、`X509SvidIssuer`（自建 CA + 签发 SVID）、`X509SvidVerifier`（链校验 + 提取 SPIFFE ID）、`SpiffeAuthenticator implements Authenticator`（PEM 证书 → `Principal`）。
- **非目标**：完整 SPIRE attestation 插件谱系（k8s-sa/aws-iid 等留后续）、mTLS transport 接线（宿主层后续）、CA 轮换/中间 CA 层级、CRL/OCSP。

## 2. 架构与数据流

```
X509SvidIssuer（启动建 CA：ECDSA P-256 自签根）
  issueSvid(SpiffeId id, Duration ttl)
     → 新 keypair + X509 证书：URI SAN = id.toUri()、EKU clientAuth、短 ttl、CA 签名
     → Svid(certificate, keyPair)
        ▼ 消费方持证书调用
SpiffeAuthenticator.authenticate(pemCertChain)
  → X509SvidVerifier.verify(cert, caCert)：签名链 + 有效期 + 提取 URI SAN
  → SpiffeId → Principal(subject=spiffe uri, scopes=∅, attributes={"authn":"x509-svid"})
        ▼ 交给 DefaultSts（OBO 交集）/ PDP —— 既有链路complete复用
```
- **密码学**：证书构建用 **BouncyCastle bcpkix**（经审计库，不自写 ASN.1/X.509）；签名算法 `SHA256withECDSA`（曲线 P-256，与 IntlSuite 一致）。依赖：`bcprov-jdk18on` + `bcpkix-jdk18on` 1.78.1（identity 模块）。
- **私钥边界**：CA 私钥仅内存（生产由 engine KeyManager/Barrier 托管，宿主接线后续）；SVID 私钥只在签发返回值。

## 3. 接口契约

```java
public record SpiffeId(String trustDomain, String path) {
    public String toUri();                       // spiffe://td/path
    public static SpiffeId parse(String uri);    // 非法抛 IllegalArgumentException
}
public record Svid(java.security.cert.X509Certificate certificate, java.security.KeyPair keyPair) {}

public final class X509SvidIssuer {
    public X509SvidIssuer(String trustDomain);                       // 内建自签 CA
    public java.security.cert.X509Certificate caCertificate();
    public Svid issueSvid(SpiffeId id, java.time.Duration ttl);      // id.trustDomain 必须匹配
}
public final class X509SvidVerifier {
    public SpiffeId verify(java.security.cert.X509Certificate cert,
                           java.security.cert.X509Certificate ca);   // 失败抛 TokenException
}
public final class SpiffeAuthenticator implements Authenticator {
    public SpiffeAuthenticator(X509SvidVerifier verifier, java.security.cert.X509Certificate ca);
    public Principal authenticate(String pemCert);                   // PEM → verify → Principal
}
```

## 4. 错误处理

| 场景 | 处理 |
|---|---|
| URI 非 spiffe:// 格式 | `SpiffeId.parse` 抛 IllegalArgumentException |
| 证书过期/非本 CA 签 | `X509SvidVerifier` 抛 `TokenException` |
| 证书无 URI SAN | 抛 `TokenException`（非 SVID） |
| trustDomain 不匹配 issuer | `issueSvid` 抛 IllegalArgumentException |

## 5. 测试策略（TDD，纯单元）

- SpiffeId parse/render 往返 + 非法格式拒。
- issue→verify 往返提取出同一 SpiffeId；过期证书拒（签发 ttl=-1）；他人 CA 签发拒（两个 issuer 交叉验）。
- SpiffeAuthenticator：PEM 往返 → Principal(subject=spiffe uri)；坏 PEM 拒。

## 6. YAGNI

不做 attestation 插件、不做 CA 轮换/中间层级、不做 CRL、不接 mTLS transport（留宿主）；JWT-SVID 已有（M03 即 JWT 载体），本增量只补 X.509 载体。
