package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 降级原因，用于区分“被拒绝”与“真正调用失败”。
 */
public enum FallbackReason {
    /** 熔断器打开，调用被直接拒绝。 */
    CIRCUIT_OPEN,
    /** 半开期探测名额已满，请求被拒绝。 */
    HALF_OPEN_PROBE_REJECTED,
    /** 隔离舱并发名额耗尽，调用被拒绝。 */
    BULKHEAD_REJECTED,
    /** 调用超时。 */
    TIMEOUT,
    /** 等待同一业务键的在途结果时超时（本次未发起新的下游调用）。 */
    IN_FLIGHT_TIMEOUT,
    /** 下游真正抛出异常。 */
    DOWNSTREAM_ERROR
}
