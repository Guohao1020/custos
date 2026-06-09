package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteAeadTest {

    private final CipherSuite suite = new IntlSuite();

    private byte[] key() {
        byte[] k = new byte[32];           // AES-256
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        byte[] key = key();
        byte[] pt = "hello custos".getBytes(StandardCharsets.UTF_8);
        byte[] ct = suite.encrypt(key, pt, null);
        assertArrayEquals(pt, suite.decrypt(key, ct, null));
    }

    @Test
    void ciphertextDiffersFromPlaintextAndIsRandomized() {
        byte[] key = key();
        byte[] pt = "hello custos".getBytes(StandardCharsets.UTF_8);
        byte[] ct1 = suite.encrypt(key, pt, null);
        byte[] ct2 = suite.encrypt(key, pt, null);
        assertFalse(java.util.Arrays.equals(pt, ct1));
        assertFalse(java.util.Arrays.equals(ct1, ct2), "随机 nonce → 密文不同");
    }

    @Test
    void tamperedCiphertextThrowsIntegrityException() {
        byte[] key = key();
        byte[] ct = suite.encrypt(key, "data".getBytes(StandardCharsets.UTF_8), null);
        ct[ct.length - 1] ^= 0x01;          // 翻转一位
        assertThrows(IntegrityException.class, () -> suite.decrypt(key, ct, null));
    }

    @Test
    void wrongAadThrowsIntegrityException() {
        byte[] key = key();
        byte[] ct = suite.encrypt(key, "data".getBytes(StandardCharsets.UTF_8), "aad-1".getBytes());
        assertThrows(IntegrityException.class, () -> suite.decrypt(key, ct, "aad-2".getBytes()));
    }
}
