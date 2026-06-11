package io.custos.broker;

/** 经纪决策三态：允许 / 拒绝 / 待审批（审批闭环）。 */
public enum QueryStatus { ALLOWED, DENIED, PENDING }
