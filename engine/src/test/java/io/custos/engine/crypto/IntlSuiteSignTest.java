package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import static org.junit.jupiter.api.Assertions.*;

class IntlSuiteSignTest {

    private final CipherSuite suite = new IntlSuite();

    @Test
    void signThenVerifySucceeds() {
        KeyPair kp = suite.genSignKey();
        byte[] data = "token-payload".getBytes();
        byte[] sig = suite.sign(kp.getPrivate(), data);
        assertTrue(suite.verify(kp.getPublic(), data, sig));
    }

    @Test
    void verifyFailsOnTamperedData() {
        KeyPair kp = suite.genSignKey();
        byte[] sig = suite.sign(kp.getPrivate(), "a".getBytes());
        assertFalse(suite.verify(kp.getPublic(), "b".getBytes(), sig));
    }
}
