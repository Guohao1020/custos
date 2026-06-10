package io.custos.engine.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 可切换密码套件：默认国际标准（{@link IntlSuite}），后续可加国密 GmSuite（SM4/SM3/SM2）。
 * encrypt 输出自带随机 nonce：[nonce(12) | ciphertext+tag]。所有算法由经审计的库实现，绝不自写。
 */
public interface CipherSuite {

    /** 套件标识，写入 Barrier 封套头：0x01=intl, 0x02=gm。 */
    byte suiteId();

    /** 对称密钥字节长度（AEAD key）。Intl=32(AES-256)，Gm=16(SM4-128)。 */
    default int keyLength() { return 32; }

    /** AEAD 加密（AES-256-GCM），返回 nonce||密文+tag。aad 可为 null。 */
    byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad);

    /** AEAD 解密并校验 tag；失败抛 {@link IntegrityException}。 */
    byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad);

    /** SHA-256。 */
    byte[] hash(byte[] data);

    /** HMAC-SHA-256。 */
    byte[] hmac(byte[] key, byte[] data);

    /** 生成签名密钥对（ECDSA P-256）。 */
    KeyPair genSignKey();

    byte[] sign(PrivateKey key, byte[] data);

    boolean verify(PublicKey key, byte[] data, byte[] sig);
}
