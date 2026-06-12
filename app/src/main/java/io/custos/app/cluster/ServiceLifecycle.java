package io.custos.app.cluster;

import io.custos.app.config.CustosProperties;
import io.custos.authz.ServiceInstance;
import io.custos.authz.ServiceRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * host 在 Web 端口就绪（{@link WebServerInitializedEvent}，拿到真实端口）即把自身注册到
 * {@link ServiceRegistry}，进程关停（{@code @PreDestroy}）注销。
 *
 * <p>注册元数据仅放非敏感项（version/mcp 形态）——<b>绝不</b>放 sealed 态、密钥或任何机密（红线）。
 */
@Component
public class ServiceLifecycle implements ApplicationListener<WebServerInitializedEvent> {

    private final ServiceRegistry registry;
    private final CustosProperties props;

    public ServiceLifecycle(ServiceRegistry registry, CustosProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent e) {
        if (!props.getCluster().isRegister()) return;
        int port = e.getWebServer().getPort();
        registry.register(new ServiceInstance(props.getCluster().getServiceName(),
                localIp(), port, Map.of("version", "0.6", "mcp", "stdio")));
    }

    @PreDestroy
    public void shutdown() {
        registry.deregister();
    }

    private String localIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }
}
