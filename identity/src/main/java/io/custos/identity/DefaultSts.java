package io.custos.identity;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** OBO 编排：验用户令牌 → 取 Agent 允许集 → 三者交集取最小 → 签发委托令牌(sub=用户,act=Agent)。 */
public final class DefaultSts implements Sts {

    private final Authenticator authenticator;
    private final AgentScopeResolver resolver;
    private final TokenService tokenService;
    private final String audience;

    public DefaultSts(Authenticator authenticator, AgentScopeResolver resolver, TokenService tokenService, String audience) {
        this.authenticator = authenticator;
        this.resolver = resolver;
        this.tokenService = tokenService;
        this.audience = audience;
    }

    @Override
    public ScopedToken exchange(String userToken, AgentId agent, Set<String> requestedScope, Duration ttl) {
        Principal user = authenticator.authenticate(userToken);                 // ① 验用户令牌（失败抛 TokenException）
        Set<String> agentAllowed = resolver.allowedScopes(agent);               // ② Agent 允许集
        Set<String> effective = new LinkedHashSet<>(user.scopes());             // ③ 用户∩Agent∩请求 取最小
        effective.retainAll(agentAllowed);
        effective.retainAll(requestedScope);
        return tokenService.issueOnBehalf(user.subject(), agent.toUri(), effective, audience, ttl);   // ④ sub=用户,act=Agent
    }
}
