package io.custos.identity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存黑名单（计划 4 替换为 Nacos 配置驱动）。 */
public final class InMemoryBlacklist implements Blacklist {

    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public void revoke(String subject) { revoked.add(subject); }
    public void clear() { revoked.clear(); }

    @Override
    public boolean isRevoked(String subject) { return revoked.contains(subject); }
}
