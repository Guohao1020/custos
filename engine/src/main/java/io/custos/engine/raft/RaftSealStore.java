package io.custos.engine.raft;

import io.custos.engine.seal.SealStore;

import java.util.Optional;

/** SealStore 的 Raft 落地：只复制 wrapped_barrier/threshold/shares 等密文配置；master/keyring 永不进入。 */
public final class RaftSealStore implements SealStore {

    private static final String PREFIX = "seal/";

    private final RaftKvClient client;

    public RaftSealStore(RaftKvClient client) { this.client = client; }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(client.get(PREFIX + key));
    }

    @Override
    public void put(String key, byte[] value) {
        client.put(PREFIX + key, value);
    }
}
