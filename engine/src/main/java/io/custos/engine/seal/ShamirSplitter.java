package io.custos.engine.seal;

import com.codahale.shamir.Scheme;

import java.security.SecureRandom;
import java.util.Map;

/** Shamir 秘密分享封装：用经审计的 com.codahale:shamir 实现，绝不自写算法。 */
public final class ShamirSplitter {

    private final int n;
    private final int k;

    public ShamirSplitter(int shares, int threshold) {
        if (threshold > shares || threshold < 1) {
            throw new IllegalArgumentException("invalid shares/threshold: " + shares + "/" + threshold);
        }
        this.n = shares;
        this.k = threshold;
    }

    /** 把 secret 切成 n 片（key=分片序号 1..n）。 */
    public Map<Integer, byte[]> split(byte[] secret) {
        return new Scheme(new SecureRandom(), n, k).split(secret);
    }

    /** 用 ≥k 片重建 secret。 */
    public byte[] combine(Map<Integer, byte[]> parts) {
        return new Scheme(new SecureRandom(), n, k).join(parts);
    }
}
