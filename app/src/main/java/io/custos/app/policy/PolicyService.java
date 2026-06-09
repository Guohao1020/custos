package io.custos.app.policy;

import io.custos.app.config.CustosProperties;
import io.custos.authz.ControlPlane;

/** 把策略写入控制面（Nacos 或内存）。 */
public final class PolicyService {
    private final ControlPlane controlPlane;
    private final CustosProperties props;
    public PolicyService(ControlPlane controlPlane, CustosProperties props) { this.controlPlane = controlPlane; this.props = props; }
    public void put(String content) { controlPlane.publish(props.getNacos().getPolicyDataId(), content); }
}
