package io.custos.app.token;

import io.custos.identity.AgentId;
import io.custos.identity.TokenService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Dev/演示用的 admin-gated 令牌签发端点。
 *
 * <p>仅为让本地 demo 的 {@code /query_db} 能拿到一枚由 host 私钥签名的 {@code userToken}。
 * 生产环境中 Agent 应经身份注册 / OBO 委托流程获取作用域令牌，绝不暴露此明文签发入口；
 * 因此该端点与 /operator、/policy、/audit 一样受 Bearer admin token 保护。
 */
@RestController
@RequestMapping("/token")
public class TokenController {

    private final TokenService tokens;

    public TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/issue")
    public Map<String, String> issue(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String trustDomain = (String) b.getOrDefault("trustDomain", "corp.example");
        String agent = (String) b.getOrDefault("agent", "claude-prod");
        String session = (String) b.getOrDefault("session", "s1");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) b.getOrDefault("scopes", List.of("tool:db/query_orders"));
        long ttlMinutes = b.get("ttlMinutes") instanceof Number n ? n.longValue() : 15L;

        var token = tokens.issue(
                new AgentId(trustDomain, agent, session),
                new LinkedHashSet<>(scopes),
                "broker",
                Duration.ofMinutes(ttlMinutes));
        return Map.of("jwt", token.jwt());
    }
}
