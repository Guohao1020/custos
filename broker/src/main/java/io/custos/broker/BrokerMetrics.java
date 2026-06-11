package io.custos.broker;

import java.time.Duration;

/** 经纪层指标埋点 SPI。broker 保持框架无关——app 提供 Micrometer 实现注入,缺省 NOOP。
 *  实现须线程安全(BrokerService 可并发调用)。 */
public interface BrokerMetrics {
    /** decision ∈ {allow, deny, require-approval, allow-approved}。 */
    void recordDecision(String decision);
    /** action ∈ {created, approved, denied, consumed}。 */
    void recordApproval(String action);
    void recordCredentialIssued();
    void recordCredentialRevoked();
    void recordQueryDuration(Duration d);
    void recordPdpDecisionDuration(Duration d);
    void recordCredentialIssueDuration(Duration d);
    void recordCredentialRevokeDuration(Duration d);

    /** 空实现:不采集(测试/未装配 Micrometer 时用)。 */
    BrokerMetrics NOOP = new BrokerMetrics() {
        public void recordDecision(String decision) {}
        public void recordApproval(String action) {}
        public void recordCredentialIssued() {}
        public void recordCredentialRevoked() {}
        public void recordQueryDuration(Duration d) {}
        public void recordPdpDecisionDuration(Duration d) {}
        public void recordCredentialIssueDuration(Duration d) {}
        public void recordCredentialRevokeDuration(Duration d) {}
    };
}
