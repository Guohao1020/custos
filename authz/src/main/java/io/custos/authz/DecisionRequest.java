package io.custos.authz;

/** 决策请求（MVP）：sub=主体(agent:xxx 或 role:xxx)，obj=工具(tool:server/tool)，act=动作。 */
public record DecisionRequest(String sub, String obj, String act) {}
