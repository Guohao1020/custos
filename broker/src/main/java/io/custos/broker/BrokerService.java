package io.custos.broker;

import io.custos.authz.Decision;
import io.custos.authz.DecisionRequest;
import io.custos.authz.Effect;
import io.custos.authz.Pdp;
import io.custos.engine.approval.ApprovalStatus;
import io.custos.engine.approval.ApprovalStore;
import io.custos.engine.approval.PendingApproval;
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
 * 经纪层 PEP 编排：verify token → PDP decide（三态 ALLOW/DENY/REQUIRE_APPROVAL）→ 按 resource
 * 解析引擎 → 签发动态只读凭证 → secretless 执行 → 返回结果（无凭证）。每次决策都写入哈希链审计：
 * actor=agent、resource=tool、敏感原文（租约号）经 HMAC 脱敏。
 *
 * <p>审批闭环：决策为 REQUIRE_APPROVAL 时创建审批单并返回 PENDING+approvalId；带 approvalId 重发时
 * 校验该单已 APPROVED、未过期、agent/tool/resource 与本次一致、未被消费，通过后即标记 CONSUMED
 * 放行一次（单次防重放）。
 *
 * <p>{@code audit} 可为 null（纯单元测试聚焦 secretless 与决策时不接审计）。
 */
public final class BrokerService {

    private final TokenService tokens;
    private final Pdp pdp;
    private final ResourceManager resources;
    private final SecretlessQueryExecutor executor;
    private final AuditLog audit;
    private final ApprovalStore approvals;
    private final BrokerMetrics metrics;

    public BrokerService(TokenService tokens, Pdp pdp, ResourceManager resources,
                         SecretlessQueryExecutor executor, AuditLog audit, ApprovalStore approvals,
                         BrokerMetrics metrics) {
        this.tokens = tokens;
        this.pdp = pdp;
        this.resources = resources;
        this.executor = executor;
        this.audit = audit;
        this.approvals = approvals;
        this.metrics = metrics == null ? BrokerMetrics.NOOP : metrics;
    }

    public QueryResult queryDb(QueryIntent intent, String userToken) {
        TokenClaims claims = tokens.verify(userToken);                 // 失败抛 TokenException
        String sub = "agent:" + AgentId.parse(claims.subject()).agent();
        String obj = "tool:" + intent.tool();

        // 审批重发：带 approvalId → 校 APPROVED + 未过期 + agent/tool/resource 一致 + 未 CONSUMED，
        // 通过则立即标记 CONSUMED（单次放行、防重放）。
        if (intent.approvalId() != null && !intent.approvalId().isBlank()) {
            PendingApproval ap = approvals.get(intent.approvalId()).orElse(null);
            boolean ok = ap != null
                    && ap.status() == ApprovalStatus.APPROVED
                    && System.currentTimeMillis() < ap.expireAt()
                    && ap.agent().equals(sub)
                    && ap.tool().equals(intent.tool())
                    && ap.resource().equals(intent.resource());
            if (!ok) {
                record(sub, intent.tool(), obj, "deny", "", "approval invalid/expired/mismatch");
                metrics.recordDecision("deny");
                return QueryResult.denied("approval invalid or expired");
            }
            approvals.markConsumed(intent.approvalId());
            metrics.recordApproval("consumed");
            return issueAndRun(sub, obj, intent, "allow-approved");
        }

        long pdp0 = System.nanoTime();
        Decision d = pdp.decide(DecisionRequest.of(sub, obj, "read"));
        metrics.recordPdpDecisionDuration(Duration.ofNanos(System.nanoTime() - pdp0));
        if (d.effect() == Effect.DENY) {
            record(sub, intent.tool(), obj, "deny", "", d.reason());
            metrics.recordDecision("deny");
            return QueryResult.denied(d.reason());
        }
        if (d.effect() == Effect.REQUIRE_APPROVAL) {
            String id = approvals.create(sub, intent.tool(), intent.resource(), intent.role(), d.risk(), d.reason());
            metrics.recordApproval("created");
            record(sub, intent.tool(), obj, "require-approval", "", d.reason());
            metrics.recordDecision("require-approval");
            return QueryResult.pending(id);
        }
        return issueAndRun(sub, obj, intent, "allow");
    }

    /** 签发动态只读凭证 → secretless 执行 → 落审计 → 即用即焚撤销。allow / allow-approved 两条放行路径复用。 */
    private QueryResult issueAndRun(String sub, String obj, QueryIntent intent, String decision) {
        DbDynamicEngine engine = resources.require(intent.resource());
        long i0 = System.nanoTime();
        IssuedCred cred = engine.issue(intent.role(), Duration.ofHours(1));
        metrics.recordCredentialIssueDuration(Duration.ofNanos(System.nanoTime() - i0));
        metrics.recordCredentialIssued();
        try {
            long q0 = System.nanoTime();
            List<Map<String, Object>> rows = executor.runReadonly(engine.jdbcUrl(), cred, intent.sql());
            metrics.recordQueryDuration(Duration.ofNanos(System.nanoTime() - q0));
            record(sub, intent.tool(), obj, decision, rows.size() + " rows", cred.leaseId());
            metrics.recordDecision(decision);
            return QueryResult.ok(rows);
        } finally {
            long r0 = System.nanoTime();
            engine.revoke(cred.leaseId());     // 即用即焚（也可留至租约到期）
            metrics.recordCredentialRevokeDuration(Duration.ofNanos(System.nanoTime() - r0));
            metrics.recordCredentialRevoked();
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
