package io.custos.identity;

import java.util.Set;

/** Agent 被允许的 scope 集来源 SPI（未来接 Nacos 注册表/策略）。 */
public interface AgentScopeResolver {
    Set<String> allowedScopes(AgentId agent);
}
