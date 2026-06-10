package io.custos.engine.raft;

import io.custos.engine.storage.Storage;

import java.util.List;
import java.util.Optional;

/** Storage SPI 的 Raft 落地：值为 Barrier 密文（加解密在 service 层，Raft 层不见明文）。 */
public final class RaftStorage implements Storage {

    private static final String PREFIX = "storage/";

    private final RaftKvClient client;

    public RaftStorage(RaftKvClient client) { this.client = client; }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(client.get(PREFIX + key));
    }

    @Override
    public void put(String key, byte[] value) {
        client.put(PREFIX + key, value);
    }

    @Override
    public void delete(String key) {
        client.delete(PREFIX + key);
    }

    @Override
    public List<String> list(String prefix) {
        return client.list(PREFIX + prefix).stream().map(k -> k.substring(PREFIX.length())).toList();
    }
}
