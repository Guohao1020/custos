package io.custos.engine.bench;

import io.custos.engine.barrier.DefaultBarrier;
import io.custos.engine.crypto.IntlSuite;
import io.custos.engine.crypto.Keyring;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 吞吐 smoke（默认排除，-Dgroups=bench 显式跑）：防性能悬崖回归，不设硬阈值，只打印 ops/s 与 p99。 */
@Tag("bench")
class BarrierBenchSmokeTest {

    @Test
    void sealOpenThroughput1KiB() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Keyring kr = new Keyring();
        kr.add(1, key);
        DefaultBarrier barrier = new DefaultBarrier(new IntlSuite(), kr);

        byte[] payload = new byte[1024];
        new SecureRandom().nextBytes(payload);

        int warmup = 5_000, rounds = 50_000;
        for (int i = 0; i < warmup; i++) barrier.open(barrier.seal(payload));

        long[] lat = new long[rounds];
        long t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            long s = System.nanoTime();
            barrier.open(barrier.seal(payload));
            lat[i] = System.nanoTime() - s;
        }
        long total = System.nanoTime() - t0;

        Arrays.sort(lat);
        double opsPerSec = rounds / (total / 1e9);
        long p50 = lat[rounds / 2], p99 = lat[(int) (rounds * 0.99)];
        System.out.printf("barrier seal+open 1KiB: %.0f ops/s · p50=%dus · p99=%dus%n",
                opsPerSec, p50 / 1000, p99 / 1000);
        assertTrue(opsPerSec > 0, "完成即过——趋势看打印值");
    }
}
