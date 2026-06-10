package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class GmSuiteTest {

    private final GmSuite suite = new GmSuite();

    private byte[] key() {
        byte[] k = new byte[suite.keyLength()];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void suiteIdAndKeyLength() {
        assertEquals((byte) 0x02, suite.suiteId());
        assertEquals(16, suite.keyLength());
    }

    @Test
    void sm4GcmRoundTripsAndRejectsTamper() {
        byte[] k = key();
        byte[] ct = suite.encrypt(k, "国密明文".getBytes(), "aad".getBytes());
        assertArrayEquals("国密明文".getBytes(), suite.decrypt(k, ct, "aad".getBytes()));
        ct[ct.length - 1] ^= 1;
        assertThrows(IntegrityException.class, () -> suite.decrypt(k, ct, "aad".getBytes()));
    }

    @Test
    void sm3DigestDeterministic32Bytes() {
        byte[] h1 = suite.hash("x".getBytes());
        assertEquals(32, h1.length);
        assertArrayEquals(h1, suite.hash("x".getBytes()));
    }

    @Test
    void hmacSm3KeyedAndDeterministic() {
        byte[] m1 = suite.hmac("k1".getBytes(), "d".getBytes());
        assertArrayEquals(m1, suite.hmac("k1".getBytes(), "d".getBytes()));
        assertFalse(java.util.Arrays.equals(m1, suite.hmac("k2".getBytes(), "d".getBytes())));
    }

    @Test
    void sm2SignVerifyAndWrongKeyRejected() {
        KeyPair kp = suite.genSignKey();
        byte[] sig = suite.sign(kp.getPrivate(), "msg".getBytes());
        assertTrue(suite.verify(kp.getPublic(), "msg".getBytes(), sig));
        assertFalse(suite.verify(suite.genSignKey().getPublic(), "msg".getBytes(), sig));
    }
}
