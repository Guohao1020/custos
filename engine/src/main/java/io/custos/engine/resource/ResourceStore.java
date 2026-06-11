package io.custos.engine.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.custos.engine.storage.Storage;
import java.util.List;
import java.util.Optional;

/** 资源记录持久化：序列化为 JSON 交给（Barrier 加密的）Storage，key=resource/&lt;name&gt;。 */
public final class ResourceStore {
    private static final String PREFIX = "resource/";
    private static final ObjectMapper OM = new ObjectMapper();
    private final Storage storage;
    public ResourceStore(Storage storage) { this.storage = storage; }

    public void put(ResourceRecord r) {
        try { storage.put(PREFIX + r.name(), OM.writeValueAsBytes(r)); }
        catch (Exception e) { throw new IllegalStateException("serialize resource failed: " + r.name(), e); }
    }
    public Optional<ResourceRecord> get(String name) {
        return storage.get(PREFIX + name).map(bytes -> {
            try { return OM.readValue(bytes, ResourceRecord.class); }
            catch (Exception e) { throw new IllegalStateException("deserialize resource failed: " + name, e); }
        });
    }
    public List<String> listNames() {
        return storage.list(PREFIX).stream().map(k -> k.substring(PREFIX.length())).toList();
    }
    public void delete(String name) { storage.delete(PREFIX + name); }
}
