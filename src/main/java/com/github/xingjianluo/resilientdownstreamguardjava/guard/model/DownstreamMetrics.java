package com.github.xingjianluo.resilientdownstreamguardjava.guard.model;

/**
 * 单个下游的可观测指标（自启动以来的累计计数，不随滑动窗口丢弃）。
 */
public final class DownstreamMetrics {
    private final String downstream;
    private volatile long successCount;
    private volatile long failureCount;
    private volatile long timeoutCount;
    private volatile long fallbackCount;
    private volatile long rejectedCount;
    private volatile long duplicateCount;
    private volatile long totalCount;

    public DownstreamMetrics(String downstream) {
        this.downstream = downstream;
    }

    public synchronized void recordSuccess() { successCount++; totalCount++; }
    public synchronized void recordFailure() { failureCount++; totalCount++; }
    public synchronized void recordTimeout() { timeoutCount++; totalCount++; }
    public synchronized void recordFallback() { fallbackCount++; }
    public synchronized void recordRejected() { rejectedCount++; }
    public synchronized void recordDuplicate() { duplicateCount++; }
    public synchronized void reset() {
        successCount = failureCount = timeoutCount = fallbackCount =
                rejectedCount = duplicateCount = totalCount = 0L;
    }

    public String downstream() { return downstream; }
    public synchronized long successCount() { return successCount; }
    public synchronized long failureCount() { return failureCount; }
    public synchronized long timeoutCount() { return timeoutCount; }
    public synchronized long fallbackCount() { return fallbackCount; }
    public synchronized long rejectedCount() { return rejectedCount; }
    public synchronized long duplicateCount() { return duplicateCount; }
    public synchronized long totalCount() { return totalCount; }
}
