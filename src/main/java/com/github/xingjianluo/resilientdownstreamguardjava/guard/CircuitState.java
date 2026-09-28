package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/** 熔断器状态：关闭（放行）/ 打开（拒绝）/ 半开（受控探测）。 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
