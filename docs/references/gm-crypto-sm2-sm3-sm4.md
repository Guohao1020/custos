# 国密算法 SM2 / SM3 / SM4 与 合规库

- **标题**：国密商用密码算法 SM2 / SM3 / SM4 与 合规实现库（BouncyCastle GM、Tongsuo 铜锁）
- **来源 URL**：
  - 算法标准：GM/T 0003（SM2 椭圆曲线公钥）、GM/T 0004（SM3 杂凑）、GM/T 0002（SM4 分组密码）；ISO 收录（SM2/SM3 ISO/IEC 14888-3 / 10118-3，SM4 ISO/IEC 18033-3）
  - 国家密码管理局：https://www.oscca.gov.cn/
  - BouncyCastle：https://www.bouncycastle.org/ （含 GM 国密实现）
  - Tongsuo（铜锁，openAtom）：https://github.com/Tongsuo-Project/Tongsuo
- **校订**：2026-06（以官方标准与合规库最新版本为准）

## 核心要点（中文摘要）

- **SM2**：基于椭圆曲线的公钥算法，用于**数字签名、密钥交换、公钥加密**，对标 ECDSA/ECDH（256-bit 曲线）。
- **SM3**：密码杂凑算法，输出 **256-bit**，对标 SHA-256，用于完整性/HMAC/哈希链。
- **SM4**：分组密码，**128-bit 分组 / 128-bit 密钥**，对标 AES-128；可用 GCM/CTR 等模式（SM4-GCM 提供 AEAD）。
- **合规库**：
  - **BouncyCastle（含 GM 扩展）**：Java 生态成熟，提供 SM2/SM3/SM4，审计充分——Custos 首选。
  - **Tongsuo（铜锁）**：国产密码库，支持国密套件与 **TLCP（国密双证书 TLS）**，信创合规强；可经 JNI/独立进程使用。
- **信创/合规意义**：金融、政企等"自主可控/信创"场景常要求国密；支持国密是 Custos 的差异化卖点之一。

## 对 Custos 的影响

- `02` 的 **CipherSuite 抽象**：默认国际套件（AES-256-GCM/SHA-256/ECDSA），**可一键切换国密套件（SM4-GCM/SM3/SM2）**；落盘格式带 `suite_id` 支持迁移。
- Barrier 用 SM4-GCM、哈希链/HMAC 用 SM3、身份签名用 SM2。
- **不自创密码学**：仅用 BouncyCastle-GM / Tongsuo 实现**标准国密算法**，绝不自写。
- 决策（已拍板）：**默认国际套件 + 国密可切换**（`02` 决策点③）。
