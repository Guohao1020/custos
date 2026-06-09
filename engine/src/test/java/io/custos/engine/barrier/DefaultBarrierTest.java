package io.custos.engine.barrier;

import io.custos.engine.crypto.IntegrityException;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class DefaultBarrierTest {

    private Keyring keyringWith(int version) {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Keyring kr = new Keyring();
        kr.add(version, k);
        return kr;
    }

    @Test
    void sealThenOpenRoundTrips() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(1));
        byte[] pt = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = barrier.seal(pt);
        assertArrayEquals(pt, barrier.open(sealed));
    }

    @Test
    void envelopeHeaderCarriesSuiteIdAndVersion() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(7));
        byte[] sealed = barrier.seal("x".getBytes());
        assertEquals(0x01, sealed[0], "suite_id");
        int version = ((sealed[1] & 0xff) << 24) | ((sealed[2] & 0xff) << 16)
                | ((sealed[3] & 0xff) << 8) | (sealed[4] & 0xff);
        assertEquals(7, version, "key_version");
    }

    @Test
    void tamperedEnvelopeFailsIntegrity() {
        Barrier barrier = new DefaultBarrier(new IntlSuite(), keyringWith(1));
        byte[] sealed = barrier.seal("x".getBytes());
        sealed[sealed.length - 1] ^= 0x01;
        assertThrows(IntegrityException.class, () -> barrier.open(sealed));
    }
}
