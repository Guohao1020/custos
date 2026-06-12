package io.custos.authz;

import java.util.List;

/** 单节点占位:register 仅记住 self,peers 返回仅含 self。 */
public final class NoOpServiceRegistry implements ServiceRegistry {
    private volatile ServiceInstance self;

    @Override
    public void register(ServiceInstance self) {
        this.self = self;
    }

    @Override
    public void deregister() {
        this.self = null;
    }

    @Override
    public List<ServiceInstance> peers() {
        return self == null ? List.of() : List.of(self);
    }
}
