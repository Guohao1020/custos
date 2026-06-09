package io.custos.broker;

import io.custos.authz.Decision;
import io.custos.authz.DecisionRequest;
import io.custos.authz.Pdp;
import io.custos.engine.secrets.IssuedCred;
import io.custos.engine.secrets.SecretsEngine;
import io.custos.identity.AgentId;
import io.custos.identity.TokenClaims;
import io.custos.identity.TokenService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 经纪层 PEP 编排：verify token → PDP decide → 签发动态只读凭证 → secretless 执行 → 返回结果（无凭证）。
 * 注：审计在完整接线（app）中注入 AuditLog；本编排留 audit 钩子（MVP 测试聚焦 secretless 与决策）。
 */
public final class BrokerService {

    private final TokenService tokens;
    private final Pdp pdp;
    private final SecretsEngine creds;
    private final SecretlessQueryExecutor executor;
    private final String jdbcUrl;

    public BrokerService(TokenService tokens, Pdp pdp, SecretsEngine creds,
                         SecretlessQueryExecutor executor, String jdbcUrl) {
        this.tokens = tokens;
        this.pdp = pdp;
        this.creds = creds;
        this.executor = executor;
        this.jdbcUrl = jdbcUrl;
    }

    public QueryResult queryDb(QueryIntent intent, String userToken) {
        TokenClaims claims = tokens.verify(userToken);                 // 失败抛 TokenException
        String sub = "agent:" + AgentId.parse(claims.subject()).agent();
        Decision d = pdp.decide(DecisionRequest.of(sub, "tool:" + intent.tool(), "read"));
        if (!d.allowed()) {
            return QueryResult.denied(d.reason());
        }
        IssuedCred cred = creds.issue(intent.schema(), Duration.ofHours(1));
        try {
            List<Map<String, Object>> rows = executor.runReadonly(jdbcUrl, cred, intent.sql());
            return QueryResult.ok(rows);
        } finally {
            creds.revoke(cred.leaseId());     // 即用即焚（也可留至租约到期）
        }
    }
}
