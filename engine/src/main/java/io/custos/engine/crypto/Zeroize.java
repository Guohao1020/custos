package io.custos.engine.crypto;

import java.util.Arrays;

/** 密钥材料集中清零。 */
public final class Zeroize {

    private Zeroize() {}

    public static void wipe(byte[]... arrays) {
        if (arrays == null) return;
        for (byte[] a : arrays) {
            if (a != null) Arrays.fill(a, (byte) 0);
        }
    }
}
