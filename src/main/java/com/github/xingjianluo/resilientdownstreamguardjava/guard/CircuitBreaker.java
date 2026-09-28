package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单下游熔断器。
 *
 * 规则：
 * - CLOSED：统计最近 windowSize 次结果；达到 minimumCalls 且失败率 &gt;= 阈值时进入 OPEN；
 * - OPEN：所有请求被拒绝（计 CIRCUIT_OPEN）；冷却期结束后，下一次评估进入 HALF_OPEN；
 * - HALF_OPEN：并发探测不超过 halfOpenMaxProbes，其余请求仍按熔断打开拒绝；
 *   累计成功达到 halfOpenSuccessToClose -> CLOSED（清空窗口重新统计）；
 *   任意一次失败 -> 立即回到 OPEN，并以当前时间重新开始冷却（防止抖动）。
 *
 * 所有状态读取与迁移都在同一监视器内完成；时间完全来自 {@link Clock}，测试可控可复现。
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final DownstreamConfig config;
    private final Clock clock;
    private final GuardMetrics metrics;
    private final StatsWindow closedWindow;

    private CircuitState state = CircuitState.CLOSED;
    private long openedAtMillis;
    private int probesInFlight;
    private int halfOpenSuccesses;
    private int halfOpenFailures;

    public CircuitBreaker(String name, DownstreamConfig config, Clock clock, GuardMetrics metrics) {
        this.name = name;
        this.config = config;
        this.clock = clock;
        this.metrics = metrics;
        this.closedWindow = new StatsWindow(config.getWindowSize(), config.getMinimumCalls());
    }

    /**
     * 当前状态；若处于 OPEN 且冷却期已满，则迁移到 HALF_OPEN。
     */
    public synchronized CircuitState currentState() {
        if (state == CircuitState.OPEN
                && clock.nowMillis() - openedAtMillis >= config.getOpenStateCooldown().toMillis()) {
            transitionTo(CircuitState.HALF_OPEN);
            probesInFlight = 0;
            halfOpenSuccesses = 0;
            halfOpenFailures = 0;
        }
        return state;
    }

    /**
     * 是否放行一次调用。CLOSED 直接放行；OPEN 一律拒绝；
     * HALF_OPEN 仅在探测名额未满时放行并占用一个名额。
     */
    public synchronized boolean allowRequest() {
        CircuitState s = currentState();
        if (s == CircuitState.CLOSED) {
            return true;
        }
        if (s == CircuitState.OPEN) {
            return false;
        }
        // HALF_OPEN：受控探测
        if (probesInFlight < config.getHalfOpenMaxProbes()) {
            probesInFlight++;
            log.info("[circuit:{}] HALF_OPEN probe allowed, probesInFlight={}/{}",
                    name, probesInFlight, config.getHalfOpenMaxProbes());
            return true;
        }
        log.info("[circuit:{}] HALF_OPEN probes exhausted ({}/{}), reject",
                name, probesInFlight, config.getHalfOpenMaxProbes());
        return false;
    }

    /** 记录一次成功并按规则迁移状态。 */
    public synchronized void recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            probesInFlight = Math.max(0, probesInFlight - 1);
            halfOpenSuccesses++;
            log.info("[circuit:{}] HALF_OPEN probe success ({}/{})",
                    name, halfOpenSuccesses, config.getHalfOpenSuccessToClose());
            if (halfOpenSuccesses >= config.getHalfOpenSuccessToClose()) {
                transitionTo(CircuitState.CLOSED);
                closedWindow.reset();
            }
        } else if (state == CircuitState.CLOSED) {
            closedWindow.record(true);
        }
        // OPEN 状态下理论上不会有成功结果，忽略以防误统计
    }

    /** 记录一次失败并按规则迁移状态。 */
    public synchronized void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            probesInFlight = Math.max(0, probesInFlight - 1);
            halfOpenFailures++;
            log.warn("[circuit:{}] HALF_OPEN probe failed, reopen circuit", name);
            openAgain();
        } else if (state == CircuitState.CLOSED) {
            closedWindow.record(false);
            if (closedWindow.hasEnoughSamples()
                    && closedWindow.failureRate() >= config.getFailureRateThreshold()) {
                log.warn("[circuit:{}] failure rate {}/{} >= {}, open circuit",
                        name, closedWindow.failureCount(), closedWindow.total(),
                        config.getFailureRateThreshold());
                openAgain();
            }
        }
    }

    private void openAgain() {
        transitionTo(CircuitState.OPEN);
        openedAtMillis = clock.nowMillis();
        probesInFlight = 0;
        halfOpenSuccesses = 0;
        halfOpenFailures = 0;
    }

    private void transitionTo(CircuitState target) {
        if (state == target) {
            return;
        }
        log.info("[circuit:{}] state transition {} -> {}", name, state, target);
        metrics.recordStateTransition(state, target);
        state = target;
    }

    public synchronized long openedAtMillis() {
        return state == CircuitState.OPEN ? openedAtMillis : 0L;
    }

    public synchronized int halfOpenProbesInFlight() {
        return probesInFlight;
    }

    public synchronized int halfOpenSuccesses() {
        return halfOpenSuccesses;
    }
}
