package io.custos.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/** JWT (ES256) 令牌服务：签名私钥来自注入的 KeyPair（生产由 engine KeyManager 提供，受 Barrier 保护）。 */
public final class JwtTokenService implements TokenService {

    private final KeyPair signingKey;
    private final String issuer;
    private final Blacklist blacklist;

    public JwtTokenService(KeyPair signingKey, String issuer, Blacklist blacklist) {
        this.signingKey = signingKey;
        this.issuer = issuer;
        this.blacklist = blacklist;
    }

    @Override
    public ScopedToken issue(AgentId subject, Set<String> scopes, String audience, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jwt = Jwts.builder()
                .issuer(issuer)
                .subject(subject.toUri())
                .audience().add(audience).and()
                .claim("scope", String.join(" ", scopes))
                .claim("act", "broker")
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey.getPrivate(), Jwts.SIG.ES256)
                .compact();
        return new ScopedToken(jwt, exp);
    }

    @Override
    public ScopedToken issueOnBehalf(String userSubject, String actorAgent, Set<String> scopes, String audience, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jwt = Jwts.builder()
                .issuer(issuer)
                .subject(userSubject)
                .audience().add(audience).and()
                .claim("scope", String.join(" ", scopes))
                .claim("act", actorAgent)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey.getPrivate(), Jwts.SIG.ES256)
                .compact();
        return new ScopedToken(jwt, exp);
    }

    @Override
    public TokenClaims verify(String jwt) {
        try {
            Claims c = Jwts.parser()
                    .requireIssuer(issuer)
                    .verifyWith(signingKey.getPublic())
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            if (blacklist.isRevoked(c.getSubject())) {
                throw new TokenException("token subject is revoked: " + c.getSubject());
            }
            Set<String> scopes = new LinkedHashSet<>();
            String scope = c.get("scope", String.class);
            if (scope != null && !scope.isBlank()) {
                for (String s : scope.split(" ")) scopes.add(s);
            }
            String aud = (c.getAudience() == null || c.getAudience().isEmpty())
                    ? null : c.getAudience().iterator().next();
            return new TokenClaims(c.getSubject(), c.get("act", String.class), scopes, aud, c.getExpiration().toInstant());
        } catch (JwtException e) {
            throw new TokenException("invalid token", e);
        }
    }
}
