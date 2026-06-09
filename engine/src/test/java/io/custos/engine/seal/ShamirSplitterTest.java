package io.custos.engine.seal;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShamirSplitterTest {

    @Test
    void splitsIntoNAndRecombinesWithThreshold() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);

        ShamirSplitter splitter = new ShamirSplitter(5, 3);
        Map<Integer, byte[]> shares = splitter.split(secret);
        assertEquals(5, shares.size());

        // 任意 3 片可重建
        Map<Integer, byte[]> subset = new HashMap<>();
        shares.entrySet().stream().limit(3).forEach(e -> subset.put(e.getKey(), e.getValue()));
        assertArrayEquals(secret, splitter.combine(subset));
    }

    @Test
    void differentSubsetOfThresholdAlsoRecovers() {
        byte[] secret = "master-key-material-0123456789ab".getBytes();
        ShamirSplitter splitter = new ShamirSplitter(5, 3);
        Map<Integer, byte[]> shares = splitter.split(secret);

        Map<Integer, byte[]> subset = new HashMap<>();
        shares.entrySet().stream().skip(2).limit(3).forEach(e -> subset.put(e.getKey(), e.getValue()));
        assertArrayEquals(secret, splitter.combine(subset));
    }
}
