package io.custos.app.monitor;

import io.custos.app.operator.OperatorService;
import io.custos.engine.lease.Lease;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** admin-gated：活跃租约只读浏览。 */
@RestController
@RequestMapping("/leases")
public class LeaseController {
    private final OperatorService op;
    public LeaseController(OperatorService op) { this.op = op; }

    @GetMapping
    public List<Lease> active() {
        try {
            return op.unsealed().leases().listActive();
        } catch (IllegalStateException sealed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sealed");
        }
    }
}
