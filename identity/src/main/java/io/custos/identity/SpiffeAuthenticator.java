package io.custos.identity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

/** PEM 证书 → 验 SVID → Principal(subject=spiffe uri)。scopes 留空（授权交 STS/PDP）。 */
public final class SpiffeAuthenticator implements Authenticator {

    private final X509SvidVerifier verifier;
    private final X509Certificate ca;

    public SpiffeAuthenticator(X509SvidVerifier verifier, X509Certificate ca) {
        this.verifier = verifier;
        this.ca = ca;
    }

    @Override
    public Principal authenticate(String pemCert) {
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pemCert.getBytes(StandardCharsets.UTF_8)));
            SpiffeId id = verifier.verify(cert, ca);
            return new Principal(id.toUri(), Set.of(), Map.of("authn", "x509-svid"));
        } catch (TokenException te) {
            throw te;
        } catch (Exception e) {
            throw new TokenException("invalid svid pem", e);
        }
    }
}
