package io.custos.engine.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** 国密套件（suiteId=0x02）：SM4-GCM / SM3 / HmacSM3 / SM2(SM3withSM2)。算法全部由 BouncyCastle 提供，绝不自写。封套与 Intl 同构：nonce(12)||ct+tag。 */
public final class GmSuite implements CipherSuite {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final int NONCE = 12, TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();

    @Override
    public byte suiteId() { return 0x02; }

    @Override
    public int keyLength() { return 16; }   // SM4-128

    @Override
    public byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sm4 encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext, byte[] aad) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE];
            bb.get(nonce);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", "BC");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IntegrityException("sm4 decrypt/verify failed", e);
        }
    }

    @Override
    public byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SM3", "BC").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSM3", "BC");
            m.init(new SecretKeySpec(key, "HmacSM3"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public KeyPair genSignKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "BC");
            g.initialize(new ECGenParameterSpec("sm2p256v1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance("SM3withSM2", "BC");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(PublicKey key, byte[] data, byte[] sig) {
        try {
            Signature s = Signature.getInstance("SM3withSM2", "BC");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
