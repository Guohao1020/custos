package io.custos.app.metrics;

import io.custos.app.operator.OperatorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** 运行态 gauge：seal 态 / 活跃租约数 / 已接入资源数 / 待审批队深。
 *  数据源与 {@link io.custos.app.monitor.MonitorController} 一致：op.status() + op.unsealed().*。
 *  解封前 op.unsealed() 会抛（M16 已知），gauge lambda 经 safe(...) 吞异常降级为 0，
 *  只有 op.status().sealed() 在任何时候都可安全调用。 */
@Component
public class MetricsConfig {

    private final OperatorService op;
    private final MeterRegistry registry;

    public MetricsConfig(OperatorService op, MeterRegistry registry) {
        this.op = op;
        this.registry = registry;
    }

    @PostConstruct
    public void register() {
        // sealed 态恒可读，1=sealed / 0=unsealed
        Gauge.builder("custos.seal.sealed", op, o -> o.status().sealed() ? 1.0 : 0.0)
                .register(registry);
        Gauge.builder("custos.leases.active", op, o -> safe(() -> o.unsealed().leases().listActive().size()))
                .register(registry);
        Gauge.builder("custos.resources.count", op, o -> safe(() -> o.unsealed().resourceManager().list().size()))
                .register(registry);
        Gauge.builder("custos.approvals.pending", op, o -> safe(() -> o.unsealed().approvals().listPending().size()))
                .register(registry);
    }

    /** sealed/未装配时 op.unsealed() 抛 IllegalStateException——吞掉降级为 0，监控面在解封前也能渲染。 */
    private double safe(Supplier<Integer> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }
}
