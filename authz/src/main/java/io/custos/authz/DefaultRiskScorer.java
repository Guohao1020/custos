package io.custos.authz;

import java.util.Map;

/** 确定性加权评分：动作危险度 + 资源分级 + 上下文异常（非工作时段/不可信 IP/可疑意图）。 */
public final class DefaultRiskScorer implements RiskScorer {

    private static final Map<String, Integer> ACTION_BASE = Map.of("read", 10, "write", 40, "delete", 70, "admin", 90);

    private final AbacPolicy policy;

    public DefaultRiskScorer(AbacPolicy policy) { this.policy = policy; }

    @Override
    public int score(DecisionRequest req) {
        RequestContext c = req.ctx();
        int s = ACTION_BASE.getOrDefault(req.act(), 30);
        s += c.intAttr("resourceLevel", 0) * 15;
        int hour = c.intAttr("hour", 12);
        if (hour < policy.workStartHour() || hour >= policy.workEndHour()) s += 20;
        if ("false".equals(c.attr("ipTrusted"))) s += 20;          // 仅显式不信任 +20
        if (c.boolAttr("intentSuspicious")) s += 25;
        return Math.max(0, Math.min(100, s));
    }
}
