package io.custos.identity;

import java.util.Map;
import java.util.Set;

/** 认证产出的主体：subject（用户身份）、scopes（用户授予）、attributes（扩展属性，ABAC 用）。 */
public record Principal(String subject, Set<String> scopes, Map<String, String> attributes) {}
