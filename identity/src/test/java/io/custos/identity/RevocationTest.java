package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RevocationTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @Test
    void revokedSubjectIsRejectedEvenWhenTokenStillValid() throws Exception {
        InMemoryBlacklist blacklist = new InMemoryBlacklist();
        TokenService svc = new JwtTokenService(ec(), "custos", blacklist);
        AgentId id = new AgentId("corp.example", "claude-prod", "s1");
        ScopedToken t = svc.issue(id, Set.of("s"), "broker", Duration.ofMinutes(15));

        assertNotNull(svc.verify(t.jwt()));          // 吊销前正常
        blacklist.revoke(id.toUri());                // 吊销
        assertThrows(TokenException.class, () -> svc.verify(t.jwt()));   // 未到 exp 也被拒
    }
}
