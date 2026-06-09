package io.custos.identity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 Agent 允许集（计划后续接 Nacos 注册表只换实现）。未登记 → 空集。 */
public final class InMemoryAgentScopeResolver implements AgentScopeResolver {

    private final Map<String, Set<String>> byAgent = new ConcurrentHashMap<>();

    public void grant(AgentId agent, Set<String> scopes) { byAgent.put(agent.toUri(), Set.copyOf(scopes)); }

    @Override
    public Set<String> allowedScopes(AgentId agent) { return byAgent.getOrDefault(agent.toUri(), Set.of()); }
}
