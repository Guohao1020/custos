package io.custos.engine.resource;

import io.custos.engine.storage.Storage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStoreTest {
    static final class MemStorage implements Storage {
        final Map<String, byte[]> m = new LinkedHashMap<>();
        public Optional<byte[]> get(String k) { return Optional.ofNullable(m.get(k)); }
        public void put(String k, byte[] v) { m.put(k, v.clone()); }
        public void delete(String k) { m.remove(k); }
        public List<String> list(String prefix) { return m.keySet().stream().filter(s -> s.startsWith(prefix)).sorted().toList(); }
    }
    private ResourceRecord rec(String name) {
        return new ResourceRecord(name, "db.relational", "mysql", "jdbc:mysql://h/" + name, "admin", "pwd",
                List.of(new RoleDef("read-only", RoleKind.BUILTIN_READONLY, List.of(), List.of(), 3600, name)));
    }
    @Test void putGetListDelete() {
        ResourceStore store = new ResourceStore(new MemStorage());
        store.put(rec("appdb")); store.put(rec("orders"));
        assertEquals("admin", store.get("appdb").orElseThrow().adminUsername());
        assertEquals(List.of("appdb", "orders"), store.listNames());
        store.delete("appdb");
        assertTrue(store.get("appdb").isEmpty());
        assertEquals(List.of("orders"), store.listNames());
    }
}
