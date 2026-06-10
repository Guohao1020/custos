package io.custos.engine.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZeroizeTest {

    @Test
    void wipesAllArrays() {
        byte[] a = {1, 2, 3}, b = {4, 5};
        Zeroize.wipe(a, b);
        assertArrayEquals(new byte[3], a);
        assertArrayEquals(new byte[2], b);
        Zeroize.wipe((byte[]) null);   // null 安全
    }

    @Test
    void keyringDestroyWipesAndBlocks() {
        Keyring kr = new Keyring();
        kr.add(1, new byte[]{9, 9, 9});
        kr.destroy();
        assertThrows(IllegalStateException.class, () -> kr.key(1));
        assertThrows(IllegalStateException.class, kr::activeVersion);
    }
}
