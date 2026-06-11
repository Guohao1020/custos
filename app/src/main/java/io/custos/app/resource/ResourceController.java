package io.custos.app.resource;

import io.custos.app.operator.OperatorService;
import io.custos.engine.resource.ResourceRecord;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** admin-gated 资源接入端点。响应绝不含 adminPassword。 */
@RestController
@RequestMapping("/resources")
public class ResourceController {
    private final OperatorService op;
    public ResourceController(OperatorService op) { this.op = op; }

    @PostMapping
    public Map<String, Object> register(@RequestBody ResourceRecord body) {
        op.unsealed().resourceManager().register(body);
        return Map.of("name", body.name(), "status", "registered");
    }
    @GetMapping
    public List<String> list() { return op.unsealed().resourceManager().list(); }

    @PostMapping("/{name}/rotate-admin")
    public Map<String, Object> rotate(@PathVariable String name, @RequestBody Map<String, String> body) {
        op.unsealed().resourceManager().rotateAdminKey(name, body.get("adminPassword"));
        return Map.of("name", name, "status", "rotated");
    }
    @DeleteMapping("/{name}")
    public Map<String, Object> remove(@PathVariable String name) {
        op.unsealed().resourceManager().unregister(name);
        return Map.of("name", name, "status", "removed");
    }
}
