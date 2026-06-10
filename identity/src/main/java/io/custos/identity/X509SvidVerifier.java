package io.custos.identity;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

/** 验证 SVID：CA 签名 + 有效期 + 提取 URI SAN → SpiffeId。失败抛 TokenException。 */
public final class X509SvidVerifier {

    public SpiffeId verify(X509Certificate cert, X509Certificate ca) {
        try {
            cert.verify(ca.getPublicKey());
            cert.checkValidity();
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if ((Integer) san.get(0) == 6) {            // 6 = URI
                        return SpiffeId.parse((String) san.get(1));
                    }
                }
            }
            throw new TokenException("no spiffe URI SAN in certificate");
        } catch (TokenException te) {
            throw te;
        } catch (Exception e) {
            throw new TokenException("invalid svid", e);
        }
    }
}
