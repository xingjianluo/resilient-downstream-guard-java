package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单下游可观测指标。计数均在同一监视器内更新，并发压力下不丢失不错乱；
 * 只保留固定数量的计数器，长期运行内存占用恒定。
 */
public final class GuardMetrics {

    private final String downstream;

    private long successCount;
    private long dedupSharedCount;
    private long fallbackCount;
    private long timeoutCount;
    private long circuitRejectCount;
    private long bulkheadRejectCount;
    private long downstreamErrorCount;

    private final EnumMap<FailureReason, Long> fallbackByReason = new EnumMap<>(FailureReason.class);
    private final Map<String, Long> stateTransitions = new LinkedHashMap<>();

    public GuardMetrics(String downstream) {
        this.downstream = downstream;
    }

    public synchronized void recordSuccess(boolean dedupShared) {
        successCount++;
        if (dedupShared) {
            dedupSharedCount++;
        }
    }

    /** 记录一次降级（被拒绝或真实失败后的兜底）。 */
    public synchronized void recordFallback(FailureReason reason) {
        fallbackCount++;
        fallbackByReason.merge(reason, 1L, Long::sum);
        switch (reason) {
            case TIMEOUT -> timeoutCount++;
            case CIRCUIT_OPEN -> circuitRejectCount++;
            case BULKHEAD_REJECTED -> bulkheadRejectCount++;
            case DOWNSTREAM_ERROR -> downstreamErrorCount++;
            default -> { /* INTERRUPTED 仅计入 fallbackByReason */ }
        }
    }

    public synchronized void recordStateTransition(CircuitState from, CircuitState to) {
        stateTransitions.merge(from + "->" + to, 1L, Long::sum);
    }

    public String getDownstream() {
        return downstream;
    }

    public synchronized long getSuccessCount() {
        return successCount;
    }

    public synchronized long getDedupSharedCount() {
        return dedupSharedCount;
    }

    public synchronized long getFallbackCount() {
        return fallbackCount;
    }

    public synchronized long getTimeoutCount() {
        return timeoutCount;
    }

    public synchronized long getCircuitRejectCount() {
        return circuitRejectCount;
    }

    public synchronized long getBulkheadRejectCount() {
        return bulkheadRejectCount;
    }

    public synchronized long getDownstreamErrorCount() {
        return downstreamErrorCount;
    }

    public synchronized Map<FailureReason, Long> getFallbackByReason() {
        return new EnumMap<>(fallbackByReason);
    }

    public synchronized Map<String, Long> getStateTransitions() {
        return new LinkedHashMap<>(stateTransitions);
    }

    @Override
    public synchronized String toString() {
        return "GuardMetrics{downstream='" + downstream + "', success=" + successCount
                + ", dedupShared=" + dedupSharedCount + ", fallback=" + fallbackCount
                + ", byReason=" + fallbackByReason + ", transitions=" + stateTransitions + '}';
    }
}
