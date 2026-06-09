package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultStsTest {

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private final AgentId agent = new AgentId("corp.example", "claude-prod", "s1");

    @Test
    void exchangeNarrowsToIntersectionWithSubAndAct() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String userJwt = svc.issueOnBehalf("user:alice", "user:alice", Set.of("a", "b", "c"), "custos", Duration.ofMinutes(15)).jwt();
        InMemoryAgentScopeResolver resolver = new InMemoryAgentScopeResolver();
        resolver.grant(agent, Set.of("b", "c", "d"));

        Sts sts = new DefaultSts(new JwtAuthenticator(svc), resolver, svc, "broker");
        ScopedToken obo = sts.exchange(userJwt, agent, Set.of("c", "d"), Duration.ofMinutes(15));

        TokenClaims c = svc.verify(obo.jwt());
        assertEquals("user:alice", c.subject(), "sub=用户");
        assertEquals(agent.toUri(), c.actor(), "act=Agent");
        assertEquals(Set.of("c"), c.scopes(), "交集 {a,b,c}∩{b,c,d}∩{c,d} = {c}");
    }

    @Test
    void emptyIntersectionYieldsEmptyScope() throws Exception {
        TokenService svc = new JwtTokenService(ec(), "custos", new InMemoryBlacklist());
        String userJwt = svc.issueOnBehalf("user:bob", "user:bob", Set.of("a"), "custos", Duration.ofMinutes(15)).jwt();
        InMemoryAgentScopeResolver resolver = new InMemoryAgentScopeResolver();
        resolver.grant(agent, Set.of("z"));

        Sts sts = new DefaultSts(new JwtAuthenticator(svc), resolver, svc, "broker");
        ScopedToken obo = sts.exchange(userJwt, agent, Set.of("a"), Duration.ofMinutes(15));
        assertTrue(svc.verify(obo.jwt()).scopes().isEmpty(), "空交集 → 空 scope");
    }
}
