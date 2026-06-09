# Vault / OpenBao：Barrier · Seal-Unseal · Dynamic Secrets · Lease 设计

- **标题**：HashiCorp Vault / OpenBao 的引擎核心设计（Barrier 加密 / Seal-Unseal / 动态密钥 / 租约）
- **来源 URL**：
  - OpenBao（MPL-2.0，可公开引用）：https://openbao.org/docs/ （internals/architecture、internals/security、concepts/seal、concepts/lease）
  - Vault（BSL-1.1，仅作设计参照）：https://developer.hashicorp.com/vault/docs/internals
- **许可证**：OpenBao = MPL-2.0（文件级 copyleft）；Vault = BSL-1.1。**均不抄码，只借设计思想。**
- **校订**：2026-06

## 核心要点（中文摘要，源自 OpenBao 官方文档）

- **Barrier（加密屏障）**：存储后端视为不可信；所有数据落盘前用 **AES-256-GCM + 96-bit 随机 nonce** 加密，读取时校验 **GCM 认证标签**检测篡改。
- **密钥层级**：业务数据 ←(encryption key in **keyring**) ←(**root key**) ←(**unseal key**)。unseal key 绝不落盘。
- **Seal/Unseal**：启动 sealed；**Shamir 秘密分享**默认把 unseal key 切 N 片取 M 片（默认 5/3）重建；或 **KMS/HSM 自动解封**（用 recovery key 做高危授权）。检测入侵可一键 seal。
- **Dynamic Secrets（动态密钥）**：凭证请求时**现场生成、到期销毁**（如 database engine 的 creation/revocation statements + TTL）。
- **Lease（租约）**：每个动态密钥/service token 带 lease（TTL、可续约）；**Expiration Manager** 到期自动撤销；支持**前缀批量吊销**与 **token 级联吊销**。
- **审计**：审计先于返回密钥；对敏感字段做 **HMAC 脱敏**（注意：**默认非哈希链**）。
- **威胁模型**：显式声明"在模型内/不在模型内"（如不防运行态内存分析、不防对存储的任意控制）。

## 对 Custos 的影响

- 引擎内核（`02`）**整套借鉴思想**：Barrier、四层密钥、Shamir/KMS 双解封、Lease/撤销、动态凭证、显式威胁模型。
- **差异化升级**：审计从 HMAC 脱敏**升级为哈希链防篡改**；吊销与 **Nacos 秒级热推**打通。
- **许可红线**：OpenBao(MPL 文件级 copyleft)/Vault(BSL) 代码**严禁混入** Custos（Apache-2.0）；需公开引用时用 OpenBao 文档。
