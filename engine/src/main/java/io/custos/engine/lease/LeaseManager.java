package io.custos.engine.lease;

import java.time.Duration;

public interface LeaseManager {
    Lease register(String resourcePath, Duration ttl, Revoker revoker);
    Lease renew(String leaseId, Duration increment);
    void revoke(String leaseId);
    int revokePrefix(String prefix);
}
