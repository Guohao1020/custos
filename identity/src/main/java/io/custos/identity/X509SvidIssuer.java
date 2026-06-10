package io.custos.identity;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** 自建根 CA（ECDSA P-256 自签）并签发携 SPIFFE URI SAN 的短时 SVID。CA 私钥仅内存（生产由 KeyManager/Barrier 托管）。 */
public final class X509SvidIssuer {

    private final String trustDomain;
    private final KeyPair caKey;
    private final X509Certificate caCert;
    private final AtomicLong serial = new AtomicLong(1);

    public X509SvidIssuer(String trustDomain) {
        try {
            this.trustDomain = trustDomain;
            this.caKey = ecKeyPair();
            X500Name subject = new X500Name("CN=custos-ca,O=" + trustDomain);
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(serial.getAndIncrement()),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(365))),
                    subject, caKey.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            this.caCert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException("build CA failed", e);
        }
    }

    public X509Certificate caCertificate() { return caCert; }

    public Svid issueSvid(SpiffeId id, Duration ttl) {
        if (!trustDomain.equals(id.trustDomain())) {
            throw new IllegalArgumentException("trust domain mismatch: " + id.trustDomain());
        }
        try {
            KeyPair kp = ecKeyPair();
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    new X500Name("CN=custos-ca,O=" + trustDomain), BigInteger.valueOf(serial.getAndIncrement()),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(ttl)),
                    new X500Name("CN=" + id.path().replace('/', '-')), kp.getPublic());
            b.addExtension(Extension.subjectAlternativeName, true,
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, id.toUri())));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
            return new Svid(cert, kp);
        } catch (Exception e) {
            throw new IllegalStateException("issue svid failed", e);
        }
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
}
