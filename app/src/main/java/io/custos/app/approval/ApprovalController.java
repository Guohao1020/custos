package io.custos.app.approval;

import io.custos.app.operator.OperatorService;
import io.custos.engine.approval.PendingApproval;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** admin-gated 审批队列：列出待批、批准/拒绝单条。批准给 15 分钟有效窗。 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    /** 批准后的有效窗（ms）：携 approvalId 的 query_db 须在此窗内放行。 */
    private static final long APPROVAL_WINDOW_MS = 15 * 60_000L;

    private final OperatorService op;

    public ApprovalController(OperatorService op) { this.op = op; }

    @GetMapping
    public List<PendingApproval> pending() {
        return op.unsealed().approvals().listPending();
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") String id) {
        op.unsealed().approvals().approve(id, System.currentTimeMillis() + APPROVAL_WINDOW_MS);
        return Map.of("id", id, "status", "approved");
    }

    @PostMapping("/{id}/deny")
    public Map<String, Object> deny(@PathVariable("id") String id) {
        op.unsealed().approvals().deny(id);
        return Map.of("id", id, "status", "denied");
    }
}
