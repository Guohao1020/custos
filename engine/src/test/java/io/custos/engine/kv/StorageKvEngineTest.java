package io.custos.engine.kv;

import io.custos.engine.storage.Storage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StorageKvEngineTest {

    /** 内存 Storage 替身（无 DB/Barrier；密文性由 Storage 实现自身的 IT 保证）。 */
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new HashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String p) { return m.keySet().stream().filter(k -> k.startsWith(p)).sorted().toList(); }
    }

    private final KvEngine kv = new StorageKvEngine(new MemStorage());

    @Test
    void putIncrementsVersionAndGetLatest() {
        assertEquals(1, kv.put("app/api-key", "v1".getBytes()));
        assertEquals(2, kv.put("app/api-key", "v2".getBytes()));
        assertArrayEquals("v2".getBytes(), kv.get("app/api-key").orElseThrow());
        assertEquals(2, kv.currentVersion("app/api-key"));
    }

    @Test
    void getSpecificVersion() {
        kv.put("p", "a".getBytes());
        kv.put("p", "b".getBytes());
        assertArrayEquals("a".getBytes(), kv.get("p", 1).orElseThrow());
        assertArrayEquals("b".getBytes(), kv.get("p", 2).orElseThrow());
        assertTrue(kv.get("p", 3).isEmpty());
    }

    @Test
    void absentPathBehaviour() {
        assertTrue(kv.get("nope").isEmpty());
        assertEquals(0, kv.currentVersion("nope"));
    }

    @Test
    void deleteRemovesAllVersions() {
        kv.put("d", "1".getBytes());
        kv.put("d", "2".getBytes());
        kv.delete("d");
        assertTrue(kv.get("d").isEmpty());
        assertTrue(kv.get("d", 1).isEmpty());
        assertEquals(0, kv.currentVersion("d"));
    }
}
