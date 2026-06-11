package io.custos.app.policy;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/policy")
public class PolicyController {
    private final PolicyService svc;
    public PolicyController(PolicyService svc) { this.svc = svc; }

    @PostMapping
    public Map<String, Object> put(@RequestBody Map<String, String> body) {
        svc.put(body.get("content"));
        return Map.of("ok", true);
    }

    /** admin-gated：读当前生效策略文本（控制面无策略时 policy 为空串）。 */
    @GetMapping
    public Map<String, Object> current() {
        String text = svc.get();
        return Map.of("dataId", svc.dataId(), "policy", text == null ? "" : text);
    }
}
