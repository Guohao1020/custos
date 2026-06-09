package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteDigestTest {

    private final CipherSuite suite = new IntlSuite();

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        byte[] h = suite.hash("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(h));
    }

    @Test
    void hmacIsStableAndKeyDependent() {
        byte[] k1 = "key1".getBytes(), k2 = "key2".getBytes(), data = "msg".getBytes();
        assertArrayEquals(suite.hmac(k1, data), suite.hmac(k1, data));
        assertFalse(java.util.Arrays.equals(suite.hmac(k1, data), suite.hmac(k2, data)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
