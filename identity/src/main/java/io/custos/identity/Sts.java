package io.custos.identity;

import java.time.Duration;
import java.util.Set;

/** 安全令牌服务：OBO token-exchange，签发用户∩Agent∩请求 最小交集的委托令牌。 */
public interface Sts {
    ScopedToken exchange(String userToken, AgentId agent, Set<String> requestedScope, Duration ttl);
}
