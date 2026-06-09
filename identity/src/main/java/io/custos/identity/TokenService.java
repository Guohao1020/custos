package io.custos.identity;

import java.time.Duration;
import java.util.Set;

public interface TokenService {
    /** 签发 per-session 作用域令牌。actor 为 OBO 委托方（MVP 可为常量 "broker"，v0.2 接真实委托）。 */
    ScopedToken issue(AgentId subject, Set<String> scopes, String audience, Duration ttl);

    /** 校验签名/过期/黑名单，返回声明；失败抛 {@link TokenException}。 */
    TokenClaims verify(String jwt);
}
