package io.custos.authz;

import java.util.List;

/** 服务注册发现 SPI。server-addr 空→NoOp(单节点);非空→Nacos NamingService。 */
public interface ServiceRegistry {
    void register(ServiceInstance self);

    void deregister();

    /** 当前健康的同名服务实例(含 self)。 */
    List<ServiceInstance> peers();
}
