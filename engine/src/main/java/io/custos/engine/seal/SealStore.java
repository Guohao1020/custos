package io.custos.engine.seal;

import java.util.Optional;

/** 解封所需的持久化（被加密的 master、keyring 等）。计划 2/5 由 MySQL 实现，本计划用内存实现。 */
public interface SealStore {
    Optional<byte[]> get(String key);
    void put(String key, byte[] value);
}
