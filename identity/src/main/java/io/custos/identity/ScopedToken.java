package io.custos.identity;

import java.time.Instant;

public record ScopedToken(String jwt, Instant expiresAt) {}
