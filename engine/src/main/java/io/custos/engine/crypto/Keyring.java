package io.custos.engine.crypto;

import java.util.HashMap;
import java.util.Map;

/** Barrier 多版本密钥环：支持轮换（新写用 active 版本，旧数据按 version 解密）。 */
public final class Keyring {

    private final Map<Integer, byte[]> keys = new HashMap<>();
    private int activeVersion = -1;

    /** 加入一个版本的密钥并设为 active。 */
    public void add(int version, byte[] key) {
        keys.put(version, key.clone());
        this.activeVersion = version;
    }

    public byte[] key(int version) {
        byte[] k = keys.get(version);
        if (k == null) throw new IllegalStateException("unknown key version: " + version);
        return k;
    }

    public int activeVersion() {
        if (activeVersion < 0) throw new IllegalStateException("keyring is empty");
        return activeVersion;
    }
}
