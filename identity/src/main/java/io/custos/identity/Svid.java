package io.custos.identity;

import java.security.KeyPair;
import java.security.cert.X509Certificate;

/** 签发出的 X.509-SVID：证书 + 私钥对（私钥仅在签发返回值出现）。 */
public record Svid(X509Certificate certificate, KeyPair keyPair) {}
