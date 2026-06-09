package io.custos.engine.lease;

/** 撤销回调：租约到期或被吊销时执行实际清理（如 DROP USER）。 */
@FunctionalInterface
public interface Revoker {
    void revoke(Lease lease);
}
