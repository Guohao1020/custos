package io.custos.engine.secrets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAkSkProviderTest {

    @Test
    void mintProducesActiveDistinctPairs() {
        InMemoryAkSkProvider p = new InMemoryAkSkProvider();
        AkSkPair a = p.mint("aksk/app");
        AkSkPair b = p.mint("aksk/app");
        assertTrue(a.accessKeyId().startsWith("AKIA"));
        assertNotEquals(a.accessKeyId(), b.accessKeyId(), "AK 应唯一");
        assertFalse(a.secretKey().isBlank());
        assertTrue(p.isActive(a.accessKeyId()));
        assertTrue(p.isActive(b.accessKeyId()));
    }

    @Test
    void revokeDeactivates() {
        InMemoryAkSkProvider p = new InMemoryAkSkProvider();
        AkSkPair a = p.mint("aksk/app");
        p.revoke(a.accessKeyId());
        assertFalse(p.isActive(a.accessKeyId()));
    }
}
