package io.custos.engine.barrier;

import io.custos.engine.crypto.CipherSuite;
import io.custos.engine.crypto.Keyring;

import java.nio.ByteBuffer;

/** 封套格式：[suite_id(1) | key_version(4, big-endian) | cipherSuite 输出(nonce+ct+tag)]。 */
public final class DefaultBarrier implements Barrier {

    private static final int HEADER_LEN = 1 + 4;

    private final CipherSuite suite;
    private final Keyring keyring;

    public DefaultBarrier(CipherSuite suite, Keyring keyring) {
        this.suite = suite;
        this.keyring = keyring;
    }

    @Override
    public byte[] seal(byte[] plaintext) {
        int version = keyring.activeVersion();
        byte[] body = suite.encrypt(keyring.key(version), plaintext, null);
        return ByteBuffer.allocate(HEADER_LEN + body.length)
                .put(suite.suiteId())
                .putInt(version)
                .put(body)
                .array();
    }

    @Override
    public byte[] open(byte[] ciphertext) {
        ByteBuffer bb = ByteBuffer.wrap(ciphertext);
        bb.get();                       // suite_id（MVP 仅一种套件，读过即可）
        int version = bb.getInt();
        byte[] body = new byte[bb.remaining()];
        bb.get(body);
        return suite.decrypt(keyring.key(version), body, null);
    }
}
