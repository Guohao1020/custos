# SPIFFE / SPIRE（工作负载身份标准）

- **标题**：SPIFFE（Secure Production Identity Framework For Everyone）/ SPIRE（参考实现）
- **来源 URL**：
  - SPIFFE：https://spiffe.io/ · 规范 https://github.com/spiffe/spiffe
  - SPIRE：https://github.com/spiffe/spire · 文档 https://spiffe.io/docs/latest/spire-about/
- **许可证**：Apache-2.0（CNCF 毕业项目）
- **校订**：2026-06

## 核心要点（中文摘要）

- **SPIFFE ID**：`spiffe://<trust-domain>/<path>` 的 URI，给工作负载一个平台无关的可验证身份名。
- **SVID**：身份的可验证凭证，两种载体——**X.509-SVID**（SPIFFE ID 放 URI SAN，用于 mTLS）与 **JWT-SVID**（放 JWT `sub`，带 `aud`）。短 TTL、自动轮换。
- **两段式 attestation**：
  - **Node attestation**：证明节点身份（join_token、k8s_psat、云实例身份 aws_iid/azure/gcp、x509pop/tpm）——用平台已有可信信号换身份，**不预置长期密钥**。
  - **Workload attestation**：用内核/平台属性（unix uid/gid、k8s pod 标签）鉴别本机工作负载。
- **架构**：每信任域一个 SPIRE Server（签发权威/CA），每节点一个 SPIRE Agent，工作负载经 **Workload API（Unix socket）** 取 SVID。Registration entries 声明 selector→SPIFFE ID。
- **KeyManager**：签名密钥可托管 memory/disk/AWS-KMS/Azure/GCP/Vault。
- 仓库含 **Cure53 外部安全审计报告**。

## 对 Custos 的影响

- 身份层（`03`）借鉴：**不预置密钥的 attestation**、**双载体 SVID**、**URI 化身份命名**（`custos://...`）、签名密钥托管 KMS。
- 首版**轻实现**（不引入完整 SPIRE 拓扑），后续可作为 ID2 的"SPIFFE 认证方法"对接。
- Cure53 审计实践 → Custos v0.4 外部安全审计的范本（`02` §13）。
