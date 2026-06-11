package io.custos.app.audit;

import io.custos.app.operator.OperatorService;
import io.custos.engine.audit.AuditEntry;
import io.custos.engine.audit.AuditQuery;
import io.custos.engine.audit.VerifyResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/audit")
public class AuditController {
    private final OperatorService op;
    public AuditController(OperatorService op) { this.op = op; }

    @GetMapping("/verify")
    public VerifyResult verify() {
        try { return op.unsealed().audit().verify(); }
        catch (IllegalStateException sealed) { throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed"); }
    }

    /** admin-gated：审计行分页/过滤浏览（脱敏投影 AuditEntry，不含哈希链内部字段）。 */
    @GetMapping
    public Map<String, Object> browse(
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(name = "decision", required = false) String decision,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        try {
            var audit = op.unsealed().audit();
            AuditQuery q = new AuditQuery(agent, decision, from, to, page, size);
            List<AuditEntry> rows = audit.query(q);
            long total = audit.count(q);
            return Map.of("rows", rows, "total", total, "page", page, "size", size);
        } catch (IllegalStateException sealed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed");
        }
    }
}
