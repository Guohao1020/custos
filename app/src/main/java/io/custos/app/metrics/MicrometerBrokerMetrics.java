package io.custos.app.metrics;

import io.custos.broker.BrokerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** BrokerMetrics 的 Micrometer 实现。counter 按有界 tag 分（decision/action），timer 记耗时。
 *  线程安全由 Micrometer 的 MeterRegistry 保证。
 *  红线：tag 仅用有界枚举，绝不放 agent/resource/SQL/token；指标值只是计数/耗时，从不含凭证。 */
@Component
public class MicrometerBrokerMetrics implements BrokerMetrics {

    private final MeterRegistry registry;

    public MicrometerBrokerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordDecision(String decision) {
        registry.counter("custos.decisions", "decision", decision).increment();
    }

    @Override
    public void recordApproval(String action) {
        registry.counter("custos.approvals", "action", action).increment();
    }

    @Override
    public void recordCredentialIssued() {
        registry.counter("custos.credentials.issued").increment();
    }

    @Override
    public void recordCredentialRevoked() {
        registry.counter("custos.credentials.revoked").increment();
    }

    @Override
    public void recordQueryDuration(Duration d) {
        timer("custos.query.duration").record(d);
    }

    @Override
    public void recordPdpDecisionDuration(Duration d) {
        timer("custos.pdp.decision.duration").record(d);
    }

    @Override
    public void recordCredentialIssueDuration(Duration d) {
        timer("custos.credential.issue.duration").record(d);
    }

    @Override
    public void recordCredentialRevokeDuration(Duration d) {
        timer("custos.credential.revoke.duration").record(d);
    }

    private Timer timer(String name) {
        return Timer.builder(name).publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }
}
