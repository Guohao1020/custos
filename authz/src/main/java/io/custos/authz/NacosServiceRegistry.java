package io.custos.authz;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/** Nacos NamingService 实现:custos-host 注册为服务实例 + 健康发现。props 同 NacosControlPlane。 */
public final class NacosServiceRegistry implements ServiceRegistry {
    private final NamingService naming;
    private final String group;
    private volatile ServiceInstance self;

    public NacosServiceRegistry(String serverAddr, String namespace, String group,
                                String username, String password) {
        this.group = group;
        try {
            Properties props = new Properties();
            props.put("serverAddr", serverAddr);
            if (namespace != null && !namespace.isBlank()) {
                props.put("namespace", namespace);
            }
            if (username != null && !username.isBlank()) {
                props.put("username", username);
                props.put("password", password == null ? "" : password);
            }
            this.naming = NacosFactory.createNamingService(props);
        } catch (Exception e) {
            throw new IllegalStateException("nacos naming init failed", e);
        }
    }

    @Override
    public void register(ServiceInstance s) {
        this.self = s;
        try {
            Instance ins = new Instance();
            ins.setIp(s.ip());
            ins.setPort(s.port());
            ins.setHealthy(true);
            ins.setMetadata(s.metadata());
            naming.registerInstance(s.serviceName(), group, ins);
        } catch (Exception e) {
            throw new IllegalStateException("register failed", e);
        }
    }

    @Override
    public void deregister() {
        ServiceInstance s = this.self;
        if (s == null) {
            return;
        }
        try {
            Instance ins = new Instance();
            ins.setIp(s.ip());
            ins.setPort(s.port());
            naming.deregisterInstance(s.serviceName(), group, ins);
        } catch (Exception e) {
            // 关停期容错:deregister 失败不应阻断进程退出,Nacos 心跳超时后会自动剔除该实例。
        }
    }

    @Override
    public List<ServiceInstance> peers() {
        ServiceInstance s = this.self;
        if (s == null) {
            return List.of();
        }
        try {
            return naming.selectInstances(s.serviceName(), group, true).stream()
                    .map(i -> new ServiceInstance(s.serviceName(), i.getIp(), i.getPort(),
                            i.getMetadata() == null ? Map.of() : i.getMetadata()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("selectInstances failed", e);
        }
    }
}
