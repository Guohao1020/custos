package io.custos.engine.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/** 国际标准套件：AES-256-GCM / SHA-256 / HMAC-SHA-256 / ECDSA P-256，全部用 JDK 标准实现。 */
public class IntlSuite implements CipherSuite {

    private static final int NONCE_LEN = 12;     // 96-bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public byte suiteId() {
        return 0x01;
    }

    @Override
    public byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE_LEN];
            bb.get(nonce);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new IntegrityException("GCM tag mismatch", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    @Override
    public byte[] hash(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public KeyPair genSignKey() {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public byte[] sign(PrivateKey key, byte[] data) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    public boolean verify(PublicKey key, byte[] data, byte[] sig) {
        throw new UnsupportedOperationException("Task 4");
    }
}
