package io.custos.engine.secrets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 mount 名持有多个 SecretsEngine（生产可挂多个：db/、akSk/、kv/）。 */
public final class SecretsEngineRegistry {

    private final Map<String, SecretsEngine> mounts = new ConcurrentHashMap<>();

    public void mount(String name, SecretsEngine engine) {
        mounts.put(name, engine);
    }

    public SecretsEngine require(String name) {
        SecretsEngine e = mounts.get(name);
        if (e == null) throw new IllegalArgumentException("no secrets engine mounted at: " + name);
        return e;
    }
}
