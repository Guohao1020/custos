package io.custos.app.operator;

import io.custos.engine.seal.SealStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/operator")
public class OperatorController {
    private final OperatorService op;
    public OperatorController(OperatorService op) { this.op = op; }

    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody Map<String, Integer> body) {
        List<String> shares = op.init(body.getOrDefault("shares", 5), body.getOrDefault("threshold", 3));
        return Map.of("shares", shares);
    }

    @PostMapping("/unseal")
    public SealStatus unseal(@RequestBody Map<String, String> body) { return op.unseal(body.get("share")); }

    @PostMapping("/seal")
    public Map<String, Object> seal() { op.seal(); return Map.of("sealed", true); }

    @GetMapping("/status")
    public SealStatus status() { return op.status(); }
}
