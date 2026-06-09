package io.custos.engine.storage;

import java.util.List;
import java.util.Optional;

public interface Storage {
    Optional<byte[]> get(String key);
    void put(String key, byte[] value);
    void delete(String key);
    List<String> list(String prefix);
}
