package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthenticatorTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    void authenticatesCustosUserJwtToPrincipal() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String userJwt = svc.issueOnBehalf("user:alice", "user:alice", Set.of("tool:db/query_orders", "tool:db/query_users"), "custos", Duration.ofMinutes(15)).jwt();

        Authenticator auth = new JwtAuthenticator(svc);
        Principal p = auth.authenticate(userJwt);
        assertEquals("user:alice", p.subject());
        assertTrue(p.scopes().contains("tool:db/query_orders"));
        assertTrue(p.scopes().contains("tool:db/query_users"));
    }

    @Test
    void expiredUserJwtRejected() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String expired = svc.issueOnBehalf("user:alice", "user:alice", Set.of("s"), "custos", Duration.ofSeconds(-1)).jwt();
        Authenticator auth = new JwtAuthenticator(svc);
        assertThrows(TokenException.class, () -> auth.authenticate(expired));
    }
}
