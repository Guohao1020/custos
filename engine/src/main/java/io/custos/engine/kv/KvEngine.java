package io.custos.engine.kv;

import java.util.Optional;

/** 版本化 KV：静态机密的密文存取（底层 Storage 已是 Barrier 密文）。 */
public interface KvEngine {
    long put(String path, byte[] value);                // 写新版本，返回版本号(从1起)
    Optional<byte[]> get(String path);                   // 最新版本
    Optional<byte[]> get(String path, long version);
    long currentVersion(String path);                    // 0 = 不存在
    void delete(String path);                            // 删全部版本+元数据
}
