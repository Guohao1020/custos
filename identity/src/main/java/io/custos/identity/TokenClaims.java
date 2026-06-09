package io.custos.identity;

import java.time.Instant;
import java.util.Set;

/** 校验通过后的令牌声明。 */
public record TokenClaims(String subject, String actor, Set<String> scopes,
                          String audience, Instant expiresAt) {}
