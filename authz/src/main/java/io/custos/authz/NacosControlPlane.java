package io.custos.authz;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 真实控制面：策略存 Nacos 配置（Raft CP），gRPC 长连接秒级推送。 */
public final class NacosControlPlane implements ControlPlane {

    private final ConfigService configService;
    private final String group;

    public NacosControlPlane(String serverAddr, String namespace, String group) {
        this.group = group;
        try {
            Properties props = new Properties();
            props.put("serverAddr", serverAddr);
            props.put("namespace", namespace);
            this.configService = NacosFactory.createConfigService(props);
        } catch (Exception e) {
            throw new IllegalStateException("create nacos config service failed", e);
        }
    }

    @Override
    public void publish(String dataId, String content) {
        try {
            configService.publishConfig(dataId, group, content);
        } catch (Exception e) {
            throw new IllegalStateException("nacos publish failed", e);
        }
    }

    @Override
    public String get(String dataId) {
        try {
            return configService.getConfig(dataId, group, 3000);
        } catch (Exception e) {
            throw new IllegalStateException("nacos get failed", e);
        }
    }

    @Override
    public void subscribe(String dataId, Consumer<String> onChange) {
        try {
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() { return null; }
                @Override
                public void receiveConfigInfo(String configInfo) { onChange.accept(configInfo); }
            });
        } catch (Exception e) {
            throw new IllegalStateException("nacos subscribe failed", e);
        }
    }
}
