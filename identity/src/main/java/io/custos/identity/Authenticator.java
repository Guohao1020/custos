package io.custos.identity;

/** 可插拔认证 SPI：验证凭证并返回主体。MVP 为 Custos 用户 JWT；未来加 oidc/spiffe/mtls。 */
public interface Authenticator {
    /** 验证凭证（MVP 为用户 JWT 字符串），返回主体；失败抛 {@link TokenException}。 */
    Principal authenticate(String credential);
}
