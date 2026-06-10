package io.custos.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpiffeIdTest {
    @Test
    void rendersAndParses() {
        SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");
        assertEquals("spiffe://corp.example/agent/claude-prod", id.toUri());
        assertEquals(id, SpiffeId.parse("spiffe://corp.example/agent/claude-prod"));
    }

    @Test
    void rejectsNonSpiffe() {
        assertThrows(IllegalArgumentException.class, () -> SpiffeId.parse("https://x/y"));
        assertThrows(IllegalArgumentException.class, () -> SpiffeId.parse("spiffe://no-path"));
    }
}
