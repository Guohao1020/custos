package io.custos.engine.approval;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingApprovalTest {
    @Test void recordHoldsFields() {
        PendingApproval p = new PendingApproval("a1", "agent:claude-prod", "db/query_orders", "appdb", "read-only",
                55, "中风险需审批: risk=55", ApprovalStatus.PENDING, 1000L, 0L, 0L);
        assertEquals("a1", p.id());
        assertEquals(ApprovalStatus.PENDING, p.status());
        assertEquals(55, p.risk());
    }
}
