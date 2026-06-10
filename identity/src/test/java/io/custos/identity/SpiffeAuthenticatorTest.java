package io.custos.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SpiffeAuthenticatorTest {

    private static String toPem(java.security.cert.X509Certificate c) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(c.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test
    void pemRoundTripsToPrincipal() throws Exception {
        X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
        SpiffeId id = new SpiffeId("corp.example", "agent/claude-prod");
        Svid svid = issuer.issueSvid(id, Duration.ofMinutes(15));
        Authenticator auth = new SpiffeAuthenticator(new X509SvidVerifier(), issuer.caCertificate());
        Principal p = auth.authenticate(toPem(svid.certificate()));
        assertEquals(id.toUri(), p.subject());
        assertEquals("x509-svid", p.attributes().get("authn"));
    }

    @Test
    void garbagePemRejected() {
        X509SvidIssuer issuer = new X509SvidIssuer("corp.example");
        Authenticator auth = new SpiffeAuthenticator(new X509SvidVerifier(), issuer.caCertificate());
        assertThrows(TokenException.class, () -> auth.authenticate("not-a-pem"));
    }
}
