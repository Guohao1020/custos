package io.custos.engine.audit;

/** 审计查询条件。actor/decision 为精确匹配（null=不过滤）；from/to 为 ts 毫秒闭区间（null=不限）；page 从 0 起，size>0。 */
public record AuditQuery(String actor, String decision, Long from, Long to, int page, int size) {
    public AuditQuery {
        if (size <= 0) size = 20;
        if (size > 500) size = 500;
        if (page < 0) page = 0;
    }

    public static AuditQuery firstPage() {
        return new AuditQuery(null, null, null, null, 0, 20);
    }
}
