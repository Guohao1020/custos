package io.custos.authz;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 内存控制面：publish 同步回调所有订阅者（确定性测试用）。 */
public final class InMemoryControlPlane implements ControlPlane {

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    @Override
    public void publish(String dataId, String content) {
        store.put(dataId, content);
        listeners.getOrDefault(dataId, List.of()).forEach(l -> l.accept(content));
    }

    @Override
    public String get(String dataId) {
        return store.get(dataId);
    }

    @Override
    public void subscribe(String dataId, Consumer<String> onChange) {
        listeners.computeIfAbsent(dataId, k -> new CopyOnWriteArrayList<>()).add(onChange);
    }
}
