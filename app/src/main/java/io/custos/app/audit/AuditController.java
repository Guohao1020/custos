package io.custos.app.audit;

import io.custos.app.operator.OperatorService;
import io.custos.engine.audit.VerifyResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
}
