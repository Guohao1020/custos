package io.custos.app.monitor;

import io.custos.app.operator.OperatorService;
import io.custos.engine.audit.AuditQuery;
import io.custos.engine.seal.SealStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** admin-gated：运行态聚合统计。 */
@RestController
@RequestMapping("/monitor")
public class MonitorController {
    private static final int RECENT_WINDOW = 200;
    private final OperatorService op;
    public MonitorController(OperatorService op) { this.op = op; }

    @GetMapping("/stats")
    public MonitorStats stats() {
        SealStatus seal = op.status();
        // sealed 时只能报告 seal 态——其余运营组件未装配，返回零值聚合而非报错，
        // 让监控面在解封前也能看到当前进度。
        if (seal.sealed()) {
            return new MonitorStats(true, seal.threshold(), seal.progress(),
                    0L, 0L, 0, Map.of(), 0.0);
        }
        var ctx = op.unsealed();
        long auditTotal = ctx.audit().count(new AuditQuery(null, null, null, null, 0, 1));
        Map<String, Long> counts = ctx.audit().decisionCounts(0);
        Map<String, Long> recent = ctx.audit().decisionCounts(RECENT_WINDOW);
        long recentTotal = recent.values().stream().mapToLong(Long::longValue).sum();
        long recentDeny = recent.getOrDefault("deny", 0L);
        double denyRate = recentTotal == 0 ? 0.0 : (double) recentDeny / recentTotal;
        return new MonitorStats(false, seal.threshold(), seal.progress(),
                auditTotal, ctx.leases().listActive().size(), ctx.resourceManager().list().size(),
                counts, denyRate);
    }
}
