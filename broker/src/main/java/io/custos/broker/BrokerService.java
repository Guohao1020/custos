package io.custos.broker;

import io.custos.authz.Decision;
import io.custos.authz.DecisionRequest;
import io.custos.authz.Pdp;
import io.custos.engine.audit.AuditLog;
import io.custos.engine.audit.AuditRecord;
import io.custos.engine.resource.DbDynamicEngine;
import io.custos.engine.resource.ResourceManager;
import io.custos.engine.secrets.IssuedCred;
import io.custos.identity.AgentId;
import io.custos.identity.TokenClaims;
import io.custos.identity.TokenService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 经纪层 PEP 编排：verify token → PDP decide → 按 resource 解析引擎 → 签发动态只读凭证 →
 * secretless 执行 → 返回结果（无凭证）。每次决策（allow/deny）都写入哈希链审计：
 * actor=agent、resource=tool、敏感原文（租约号）经 HMAC 脱敏。
 * {@code audit} 可为 null（纯单元测试聚焦 secretless 与决策时不接审计）。
 */
public final class BrokerService {

    private final TokenService tokens;
    private final Pdp pdp;
    private final ResourceManager resources;
    private final SecretlessQueryExecutor executor;
    private final AuditLog audit;

    public BrokerService(TokenService tokens, Pdp pdp, ResourceManager resources,
                         SecretlessQueryExecutor executor, AuditLog audit) {
        this.tokens = tokens;
        this.pdp = pdp;
        this.resources = resources;
        this.executor = executor;
        this.audit = audit;
    }

    public QueryResult queryDb(QueryIntent intent, String userToken) {
        TokenClaims claims = tokens.verify(userToken);                 // 失败抛 TokenException
        String sub = "agent:" + AgentId.parse(claims.subject()).agent();
        String obj = "tool:" + intent.tool();
        Decision d = pdp.decide(DecisionRequest.of(sub, obj, "read"));
        if (!d.allowed()) {
            record(sub, intent.tool(), obj, "deny", "", d.reason());
            return QueryResult.denied(d.reason());
        }
        DbDynamicEngine engine = resources.require(intent.resource());
        IssuedCred cred = engine.issue(intent.role(), Duration.ofHours(1));
        try {
            List<Map<String, Object>> rows = executor.runReadonly(engine.jdbcUrl(), cred, intent.sql());
            record(sub, intent.tool(), obj, "allow", rows.size() + " rows", cred.leaseId());
            return QueryResult.ok(rows);
        } finally {
            engine.revoke(cred.leaseId());     // 即用即焚（也可留至租约到期）
        }
    }

    /** 落一条审计行（sensitiveRaw 会被 HMAC 脱敏，绝不明文入库）。audit 未注入时静默跳过。 */
    private void record(String actor, String task, String resource, String decision,
                        String resultDigest, String sensitiveRaw) {
        if (audit == null) return;
        audit.append(new AuditRecord(System.currentTimeMillis(), actor, task, resource,
                "read", decision, resultDigest, sensitiveRaw));
    }
}
