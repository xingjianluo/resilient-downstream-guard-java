package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 定长环形窗口，仅保留最近 N 次调用的成功/失败结果，
 * 避免随运行时间无限累积；所有计数在同一监视器内完成，并发下不丢失不错乱。
 */
public final class StatsWindow {

    private final boolean[] records;
    private final int capacity;
    private final int minimumCalls;

    private int size;
    private int cursor;
    private int successes;
    private int failures;

    public StatsWindow(int capacity, int minimumCalls) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.minimumCalls = Math.min(minimumCalls, capacity);
        this.records = new boolean[capacity];
    }

    /** 记录一次调用结果：true=成功，false=失败。 */
    public synchronized void record(boolean success) {
        if (size == capacity) {
            // 覆盖最旧的一格，先把它从计数中扣除
            boolean oldest = records[cursor];
            if (oldest) {
                successes--;
            } else {
                failures--;
            }
        } else {
            size++;
        }
        records[cursor] = success;
        cursor = (cursor + 1) % capacity;
        if (success) {
            successes++;
        } else {
            failures++;
        }
    }

    public synchronized int successCount() {
        return successes;
    }

    public synchronized int failureCount() {
        return failures;
    }

    public synchronized int total() {
        return size;
    }

    /** 已记录次数是否达到触发熔断评估所需的最少样本数。 */
    public synchronized boolean hasEnoughSamples() {
        return size >= minimumCalls;
    }

    /** 当前失败率 [0,1]；无样本时返回 0。 */
    public synchronized double failureRate() {
        return size == 0 ? 0d : (double) failures / size;
    }

    /** 清空窗口（状态切换时调用）。 */
    public synchronized void reset() {
        size = 0;
        cursor = 0;
        successes = 0;
        failures = 0;
    }
}
