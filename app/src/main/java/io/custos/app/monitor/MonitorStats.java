package io.custos.app.monitor;

import java.util.Map;

/** 监控聚合：seal 态 + 审计总数 + 活跃租约 + 资源数 + 决策计数 + 近窗拒绝率。 */
public record MonitorStats(boolean sealed, int sealThreshold, int sealProgress,
                           long auditTotal, long activeLeases, int resourceCount,
                           Map<String, Long> decisionCounts, double denyRateRecent) {}
