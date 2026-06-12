package io.custos.app.cluster;

import io.custos.authz.ServiceInstance;
import io.custos.authz.ServiceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** admin-gated：发现的活跃 host 列表（多 host/集群可见性）。无 admin token 经 AdminTokenFilter 拦成 401。 */
@RestController
@RequestMapping("/cluster")
public class ClusterController {

    private final ServiceRegistry registry;

    public ClusterController(ServiceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/peers")
    public List<ServiceInstance> peers() {
        return registry.peers();
    }
}
