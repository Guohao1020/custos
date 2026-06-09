package io.custos.authz;

import java.util.function.Consumer;

/** 控制面：发布/订阅策略配置。NacosControlPlane 为生产实现，InMemoryControlPlane 为测试实现。 */
public interface ControlPlane {
    void publish(String dataId, String content);
    String get(String dataId);
    /** 订阅变更；内容变化时回调 onChange。 */
    void subscribe(String dataId, Consumer<String> onChange);
}
