package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 受保护的下游标识。隔离、熔断、指标均按该维度分别统计，互不影响。
 */
public enum DownstreamName {
    INVENTORY,
    POINTS,
    PROFILE
}
