package io.custos.engine.lease;

import java.time.Duration;
import java.util.List;

public interface LeaseManager {
    Lease register(String resourcePath, Duration ttl, Revoker revoker);
    Lease renew(String leaseId, Duration increment);
    void revoke(String leaseId);
    int revokePrefix(String prefix);

    /** 列出活跃租约（未撤销且未过期）。供运维只读浏览。 */
    List<Lease> listActive();
}
