package io.custos.engine.secrets;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 内存模拟 AK·SK 后端（计划后续接真实 AWS STS / 阿里云 RAM 只换实现）。 */
public final class InMemoryAkSkProvider implements AkSkProvider {

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    @Override
    public AkSkPair mint(String mount) {
        String ak = "AKIA" + hex(6);   // 12 hex
        String sk = hex(16);           // 32 hex
        active.add(ak);
        return new AkSkPair(ak, sk);
    }

    @Override
    public void revoke(String accessKeyId) {
        active.remove(accessKeyId);
    }

    /** 供测试断言。 */
    public boolean isActive(String accessKeyId) {
        return active.contains(accessKeyId);
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
