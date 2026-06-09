package io.custos.authz;

/** ABAC 阈值/工作时段（PBAC：可由 Nacos 配置热更）。 */
public record AbacPolicy(int approvalThreshold, int denyThreshold, int workStartHour, int workEndHour) {
    public static AbacPolicy defaults() { return new AbacPolicy(50, 80, 9, 18); }
}
