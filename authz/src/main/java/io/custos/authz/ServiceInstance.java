package io.custos.authz;

import java.util.Map;

/** 一个 custos 服务实例的注册视图。metadata 仅放非敏感信息(version/mcpEndpoint/sealed)。 */
public record ServiceInstance(String serviceName, String ip, int port, Map<String, String> metadata) {}
