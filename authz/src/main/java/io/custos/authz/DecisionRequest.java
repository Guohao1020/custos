package io.custos.authz;

/** 决策请求：sub=主体，dom=租户域(=Nacos namespace)，obj=工具，act=动作，ctx=ABAC 上下文。 */
public record DecisionRequest(String sub, String dom, String obj, String act, RequestContext ctx) {
    /** 向后兼容工厂：dom="default"、ctx 空。 */
    public static DecisionRequest of(String sub, String obj, String act) {
        return new DecisionRequest(sub, "default", obj, act, RequestContext.empty());
    }
}
