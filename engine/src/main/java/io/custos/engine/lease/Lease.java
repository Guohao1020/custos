package io.custos.engine.lease;

/** 一次租约的快照视图。 */
public record Lease(String leaseId, String resourcePath, long issuedAt, long expireAt) {}
