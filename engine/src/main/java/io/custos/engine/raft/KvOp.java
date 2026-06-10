package io.custos.engine.raft;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Raft 日志条目：PUT/DELETE 的简单二进制编码（type|keyLen|key|valLen|val），不引第三方序列化。 */
public record KvOp(byte type, String key, byte[] value) {

    public static final byte PUT = 1;
    public static final byte DELETE = 2;

    public static KvOp put(String key, byte[] value) { return new KvOp(PUT, key, value); }
    public static KvOp delete(String key) { return new KvOp(DELETE, key, new byte[0]); }

    public ByteBuffer encode() {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + k.length + 4 + value.length);
        bb.put(type).putInt(k.length).put(k).putInt(value.length).put(value);
        bb.flip();
        return bb;
    }

    public static KvOp decode(ByteBuffer bb) {
        byte type = bb.get();
        byte[] k = new byte[bb.getInt()];
        bb.get(k);
        byte[] v = new byte[bb.getInt()];
        bb.get(v);
        return new KvOp(type, new String(k, StandardCharsets.UTF_8), v);
    }
}
