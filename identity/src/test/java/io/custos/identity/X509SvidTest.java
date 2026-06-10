package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class X509SvidTest {

    private final X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
    private final X509SvidVerifier verifier = new X509SvidVerifier();
    private final SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");

    @Test
    void issueThenVerifyExtractsSameId() {
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        assertEquals(id, verifier.verify(svid.certificate(), issuer.caCertificate()));
    }

    @Test
    void expiredSvidRejected() {
        Svid svid = issuer.issueSvid(id, Duration.ofSeconds(-120));
        assertThrows(TokenException.class, () -> verifier.verify(svid.certificate(), issuer.caCertificate()));
    }

    @Test
    void foreignCaRejected() {
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        X509SvidIssuer other = new X509SvidIssuer("corp.example");
        assertThrows(TokenException.class, () -> verifier.verify(svid.certificate(), other.caCertificate()));
    }

    @Test
    void trustDomainMismatchAtIssueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> issuer.issueSvid(new SpiffeId("evil.example", "x"), Duration.ofMinutes(5)));
    }
}
