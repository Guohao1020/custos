package io.custos.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentIdTest {

    @Test
    void rendersSpiffeStyleUri() {
        AgentId id = new AgentId("corp.example", "claude-prod", "sess-9f3a");
        assertEquals("custos://corp.example/agent/claude-prod/session/sess-9f3a", id.toUri());
    }

    @Test
    void parsesBackFromUri() {
        AgentId id = AgentId.parse("custos://corp.example/agent/claude-prod/session/sess-9f3a");
        assertEquals("corp.example", id.trustDomain());
        assertEquals("claude-prod", id.agent());
        assertEquals("sess-9f3a", id.session());
    }
}
