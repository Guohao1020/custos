package io.custos.authz;

/** 监听 ControlPlane 上的策略 dataId，变更即重载 PDP（秒级吊销）。 */
public final class PolicyWatcher {

    private final ControlPlane controlPlane;
    private final String dataId;
    private final Pdp pdp;

    public PolicyWatcher(ControlPlane controlPlane, String dataId, Pdp pdp) {
        this.controlPlane = controlPlane;
        this.dataId = dataId;
        this.pdp = pdp;
    }

    /** 初次加载当前策略，并订阅后续变更。 */
    public void start() {
        String current = controlPlane.get(dataId);
        pdp.reload(current == null ? "" : current);
        controlPlane.subscribe(dataId, pdp::reload);
    }
}
