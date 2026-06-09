package io.custos.identity;

import java.util.Map;

/** 用 TokenService.verify 验 Custos 签发的用户 JWT → Principal。 */
public final class JwtAuthenticator implements Authenticator {

    private final TokenService tokenService;

    public JwtAuthenticator(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Principal authenticate(String credential) {
        TokenClaims c = tokenService.verify(credential);   // 失败透传 TokenException
        return new Principal(c.subject(), c.scopes(), Map.of());
    }
}
