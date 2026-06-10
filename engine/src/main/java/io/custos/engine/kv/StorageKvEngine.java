package io.custos.engine.kv;

import io.custos.engine.storage.Storage;

import java.nio.ByteBuffer;
import java.util.Optional;

/** 键布局：kv/{path}#meta → 8字节BE当前版本；kv/{path}#v{n} → 数据。单写者假设（宿主串行化同 path 写）。 */
public final class StorageKvEngine implements KvEngine {

    private final Storage storage;

    public StorageKvEngine(Storage storage) { this.storage = storage; }

    @Override
    public long put(String path, byte[] value) {
        long next = currentVersion(path) + 1;
        storage.put(dataKey(path, next), value);
        storage.put(metaKey(path), ByteBuffer.allocate(8).putLong(next).array());
        return next;
    }

    @Override
    public Optional<byte[]> get(String path) {
        long v = currentVersion(path);
        return v == 0 ? Optional.empty() : get(path, v);
    }

    @Override
    public Optional<byte[]> get(String path, long version) {
        return storage.get(dataKey(path, version));
    }

    @Override
    public long currentVersion(String path) {
        return storage.get(metaKey(path)).map(b -> ByteBuffer.wrap(b).getLong()).orElse(0L);
    }

    @Override
    public void delete(String path) {
        long v = currentVersion(path);
        for (long i = 1; i <= v; i++) storage.delete(dataKey(path, i));
        storage.delete(metaKey(path));
    }

    private static String metaKey(String p) { return "kv/" + p + "#meta"; }
    private static String dataKey(String p, long v) { return "kv/" + p + "#v" + v; }
}
