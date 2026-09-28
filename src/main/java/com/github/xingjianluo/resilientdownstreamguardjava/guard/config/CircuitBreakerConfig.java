package com.github.xingjianluo.resilientdownstreamguardjava.guard.config;

/**
 * 单个下游的熔断判定规则：
 * <ul>
 *   <li>windowSize：统计窗口大小（最近 N 次调用，定长滑动窗口，长期运行不累积对象）；</li>
 *   <li>minimumNumberOfCalls：窗口内至少有多少次调用才参与失败率判定，避免冷启动误判；</li>
 *   <li>failureRateThreshold：失败率阈值 [0,1]，达到即从 CLOSED 进入 OPEN；</li>
 *   <li>waitDurationInOpenMillis：OPEN 冷却时长，冷却结束前一律快速失败；</li>
 *   <li>permittedNumberOfCallsInHalfOpen：HALF_OPEN 允许同时在途的探测请求数；</li>
 * </ul>
 */
public record CircuitBreakerConfig(int windowSize, int minimumNumberOfCalls,
                                   double failureRateThreshold, long waitDurationInOpenMillis,
                                   int permittedNumberOfCallsInHalfOpen) {

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(20, 5, 0.5d, 5_000L, 3);
    }
}
