package io.custos.engine.secrets;

import io.custos.engine.lease.Lease;
import io.custos.engine.lease.LeaseManager;

import java.time.Duration;

/** AK·SK 动态凭证引擎（SecretsEngine 第二实现）：issue/revoke 复用租约，rotate 支持 grace 多版本过渡。 */
public final class AkSkSecretsEngine implements SecretsEngine {

    private final AkSkProvider provider;
    private final LeaseManager leases;

    public AkSkSecretsEngine(AkSkProvider provider, LeaseManager leases) {
        this.provider = provider;
        this.leases = leases;
    }

    @Override
    public String type() { return "ak-sk"; }

    @Override
    public IssuedCred issue(String path, Duration ttl) {
        AkSkPair p = provider.mint(path);
        Lease lease = leases.register("aksk/" + path, ttl, l -> provider.revoke(p.accessKeyId()));
        return new IssuedCred(p.accessKeyId(), p.secretKey(), lease.leaseId(), lease.expireAt());
    }

    @Override
    public void revoke(String leaseId) {
        leases.revoke(leaseId);
    }

    /** 轮换：发新一份；grace=0 立即撤旧（硬轮换），grace>0 把旧租约续到 grace（多版本过渡，到期后台自动撤旧）。 */
    public IssuedCred rotate(String oldLeaseId, String path, Duration newTtl, Duration grace) {
        IssuedCred fresh = issue(path, newTtl);
        if (grace.isZero()) {
            leases.revoke(oldLeaseId);
        } else {
            leases.renew(oldLeaseId, grace);
        }
        return fresh;
    }
}
