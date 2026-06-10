package io.custos.engine.seal;

import io.custos.engine.crypto.CipherSuite;
import io.custos.engine.crypto.Keyring;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥层级：
 *   unseal key（= master key，经 Shamir 切片，分片不落盘）
 *     └─ 加密 → barrier key（落盘密文，key "wrapped_barrier"）
 * store 另存 shares/threshold 元数据（明文，非敏感）。
 */
public final class DefaultSealManager implements SealManager {

    private static final String K_WRAPPED_BARRIER = "wrapped_barrier";
    private static final String K_THRESHOLD = "threshold";
    private static final String K_SHARES = "shares";
    private static final int BARRIER_VERSION = 1;

    private final CipherSuite suite;
    private final SealStore store;
    private final SecureRandom random = new SecureRandom();

    // 解封态（内存）
    private Keyring keyring;                 // null = sealed
    private final Map<Integer, byte[]> collected = new HashMap<>();

    public DefaultSealManager(CipherSuite suite, SealStore store) {
        this.suite = suite;
        this.store = store;
    }

    @Override
    public List<byte[]> init(int shares, int threshold) {
        byte[] master = new byte[suite.keyLength()];
        random.nextBytes(master);
        byte[] barrierKey = new byte[suite.keyLength()];
        random.nextBytes(barrierKey);

        // master 加密 barrier key → 落盘
        store.put(K_WRAPPED_BARRIER, suite.encrypt(master, barrierKey, null));
        store.put(K_THRESHOLD, intBytes(threshold));
        store.put(K_SHARES, intBytes(shares));

        // master 切片（分片即 unseal key 的份额）
        Map<Integer, byte[]> parts = new ShamirSplitter(shares, threshold).split(master);

        // init 后保持 sealed：清零内存中的 master 与 barrier key（更安全），操作员需用分片解封
        java.util.Arrays.fill(master, (byte) 0);
        java.util.Arrays.fill(barrierKey, (byte) 0);

        // 分片序号前缀编码进字节，提交时还原
        List<byte[]> out = new ArrayList<>();
        parts.forEach((idx, share) -> out.add(prefixIndex(idx, share)));
        return out;
    }

    @Override
    public SealStatus submitUnsealKey(byte[] share) {
        if (keyring != null) return status();   // 已解封
        int threshold = readInt(K_THRESHOLD);
        int shares = readInt(K_SHARES);

        int[] idxHolder = new int[1];
        byte[] raw = stripIndex(share, idxHolder);
        collected.put(idxHolder[0], raw);

        if (collected.size() >= threshold) {
            byte[] master = new ShamirSplitter(shares, threshold).combine(new HashMap<>(collected));
            byte[] barrierKey = suite.decrypt(master, store.get(K_WRAPPED_BARRIER)
                    .orElseThrow(() -> new IllegalStateException("not initialized")), null);
            this.keyring = new Keyring();
            this.keyring.add(BARRIER_VERSION, barrierKey);
            java.util.Arrays.fill(master, (byte) 0);
            collected.clear();
        }
        return status();
    }

    @Override
    public void seal() {
        this.keyring = null;
        collected.clear();
    }

    @Override
    public SealStatus status() {
        int threshold = store.get(K_THRESHOLD).map(DefaultSealManager::toInt).orElse(0);
        boolean sealed = (keyring == null);
        return new SealStatus(sealed, sealed ? collected.size() : threshold, threshold);
    }

    /** 解封后供 Barrier 使用的密钥环。 */
    public Keyring keyring() {
        if (keyring == null) throw new SealedException();
        return keyring;
    }

    // --- helpers ---
    private int readInt(String key) {
        return store.get(key).map(DefaultSealManager::toInt)
                .orElseThrow(() -> new IllegalStateException("not initialized: " + key));
    }

    private static byte[] intBytes(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static int toInt(byte[] b) {
        return ByteBuffer.wrap(b).getInt();
    }

    private static byte[] prefixIndex(int idx, byte[] share) {
        return ByteBuffer.allocate(4 + share.length).putInt(idx).put(share).array();
    }

    private static byte[] stripIndex(byte[] in, int[] idxOut) {
        ByteBuffer bb = ByteBuffer.wrap(in);
        idxOut[0] = bb.getInt();
        byte[] raw = new byte[bb.remaining()];
        bb.get(raw);
        return raw;
    }
}
