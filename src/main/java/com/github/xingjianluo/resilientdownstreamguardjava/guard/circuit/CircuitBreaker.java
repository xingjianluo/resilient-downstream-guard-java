package com.github.xingjianluo.resilientdownstreamguardjava.guard.circuit;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.CircuitBreakerConfig;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.CircuitState;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.CircuitStatus;

import java.util.Optional;

/**
 * 单下游熔断器：CLOSED -&gt; OPEN -&gt; HALF_OPEN -&gt; CLOSED/OPEN。
 * <p>规则（均基于 {@link TimeSource} 当前时间与定长滑动窗口统计）：
 * <ul>
 *   <li>CLOSED：窗口内调用数达到 minimumNumberOfCalls 且失败率 ≥ 阈值时转入 OPEN；</li>
 *   <li>OPEN：冷却期内全部快速失败（CIRCUIT_OPEN）；冷却到期后下一个请求把状态切到
 *       HALF_OPEN，并占用一个在途探测名额；</li>
 *   <li>HALF_OPEN：最多 permittedNumberOfCallsInHalfOpen 个在途探测，名额满后请求被拒绝
 *       （HALF_OPEN_PROBE_REJECTED）；任一探测失败立即回 OPEN 并重新计时冷却；
 *       所有探测名额都成功后才回到 CLOSED。</li>
 * </ul>
 * 所有方法在同一把锁上完成检查与状态迁移，不会出现状态抖动或重复计时。
 */
public final class CircuitBreaker {

    private final String downstreamName;
    private final CircuitBreakerConfig config;
    private final TimeSource timeSource;
    private final SlidingWindow window;

    private CircuitState state = CircuitState.CLOSED;
    private long openedAtMillis = -1L;
    private int activeProbes;
    private int successProbes;
    private long transitionCount;

    public CircuitBreaker(String downstreamName, CircuitBreakerConfig config, TimeSource timeSource) {
        this.downstreamName = downstreamName;
        this.config = config;
        this.timeSource = timeSource;
        this.window = new SlidingWindow(config.windowSize());
    }

    /**
     * 在发起调用前尝试获取放行许可。
     * @return 放行返回空；拒绝时返回降级原因
     */
    public synchronized Optional<FallbackReason> tryAcquirePermission() {
        switch (state) {
            case CLOSED -> {
                return Optional.empty();
            }
            case OPEN -> {
                long now = timeSource.nowMillis();
                if (now - openedAtMillis < config.waitDurationInOpenMillis()) {
                    return Optional.of(FallbackReason.CIRCUIT_OPEN);
                }
                // 冷却到期：进入半开并占用首个探测名额
                toHalfOpen(now);
                return Optional.empty();
            }
            case HALF_OPEN -> {
                if (activeProbes < config.permittedNumberOfCallsInHalfOpen()) {
                    activeProbes++;
                    return Optional.empty();
                }
                return Optional.of(FallbackReason.HALF_OPEN_PROBE_REJECTED);
            }
            default -> {
                return Optional.of(FallbackReason.CIRCUIT_OPEN);
            }
        }
    }

    /** 记录一次成功调用（可能触发 HALF_OPEN -&gt; CLOSED）。 */
    public synchronized void recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            activeProbes = Math.max(0, activeProbes - 1);
            successProbes++;
            if (successProbes >= config.permittedNumberOfCallsInHalfOpen()) {
                toClosed();
            }
            return;
        }
        if (state == CircuitState.CLOSED) {
            window.record(true);
        }
        // OPEN 状态下理论上不会有成功结果，忽略以免抖动
    }

    /** 记录一次失败调用（可能触发 CLOSED -&gt; OPEN 或 HALF_OPEN -&gt; OPEN）。 */
    public synchronized void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            // 探测失败：立即重新打开并重置冷却，避免反复探测抖动
            toOpen(timeSource.nowMillis());
            return;
        }
        if (state == CircuitState.CLOSED) {
            window.record(false);
            int total = window.total();
            if (total >= config.minimumNumberOfCalls()) {
                double ratio = (double) window.failures() / total;
                if (ratio >= config.failureRateThreshold()) {
                    toOpen(timeSource.nowMillis());
                }
            }
        }
    }

    public synchronized CircuitStatus snapshot() {
        int total = window.total();
        int failures = window.failures();
        double ratio = total == 0 ? 0d : (double) failures / total;
        long nextProbe = state == CircuitState.OPEN
                ? openedAtMillis + config.waitDurationInOpenMillis() : -1L;
        return new CircuitStatus(state, openedAtMillis, nextProbe, activeProbes,
                failures, total, ratio);
    }

    public synchronized CircuitState currentState() {
        return state;
    }

    public synchronized long transitionCount() {
        return transitionCount;
    }

    private void toOpen(long now) {
        CircuitState from = state;
        state = CircuitState.OPEN;
        openedAtMillis = now;
        activeProbes = 0;
        successProbes = 0;
        transitionCount++;
        logTransition(from, CircuitState.OPEN, now, null);
    }

    private void toHalfOpen(long now) {
        CircuitState from = state;
        state = CircuitState.HALF_OPEN;
        activeProbes = 1;
        successProbes = 0;
        transitionCount++;
        logTransition(from, CircuitState.HALF_OPEN, now, "probe permitted=1/" + config.permittedNumberOfCallsInHalfOpen());
    }

    private void toClosed() {
        CircuitState from = state;
        state = CircuitState.CLOSED;
        openedAtMillis = -1L;
        activeProbes = 0;
        successProbes = 0;
        window.reset();
        transitionCount++;
        logTransition(from, CircuitState.CLOSED, timeSource.nowMillis(), "all probes succeeded, window reset");
    }

    private void logTransition(CircuitState from, CircuitState to, long at, String detail) {
        System.out.printf("[circuit:%s] %s -> %s at=%d%s%n", downstreamName, from, to, at,
                detail == null ? "" : " (" + detail + ")");
    }
}
