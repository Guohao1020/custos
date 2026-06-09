package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private final AgentId id = new AgentId("corp.example", "claude-prod", "s1");

    @Test
    void issueThenVerifyReturnsClaims() throws Exception {
        KeyPair kp = ec();
        TokenService svc = new JwtTokenService(kp, "custos", new InMemoryBlacklist());
        ScopedToken t = svc.issue(id, Set.of("tool:db/query_orders"), "broker", Duration.ofMinutes(15));
        TokenClaims c = svc.verify(t.jwt());
        assertEquals(id.toUri(), c.subject());
        assertTrue(c.scopes().contains("tool:db/query_orders"));
        assertEquals("broker", c.audience());
    }

    @Test
    void expiredTokenFails() throws Exception {
        KeyPair kp = ec();
        TokenService svc = new JwtTokenService(kp, "custos", new InMemoryBlacklist());
        ScopedToken t = svc.issue(id, Set.of("s"), "broker", Duration.ofSeconds(-1));   // 已过期
        assertThrows(TokenException.class, () -> svc.verify(t.jwt()));
    }

    @Test
    void tokenSignedByOtherKeyFails() throws Exception {
        ScopedToken t = new JwtTokenService(ec(), "custos", new InMemoryBlacklist())
                .issue(id, Set.of("s"), "broker", Duration.ofMinutes(15));
        TokenService other = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        assertThrows(TokenException.class, () -> other.verify(t.jwt()));
    }
}
