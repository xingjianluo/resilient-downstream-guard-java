package com.github.xingjianluo.resilientdownstreamguardjava.guard.circuit;

/**
 * 定长布尔滑动窗口：记录最近 N 次调用的成功/失败，窗口满后最老记录被覆盖，
 * 不会随运行时间无限增长。
 */
public final class SlidingWindow {

    private final boolean[] samples;
    private final int capacity;
    private int head;
    private int size;
    private int failures;

    public SlidingWindow(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("window size must be positive: " + size);
        }
        this.capacity = size;
        this.samples = new boolean[size];
    }

    /** 记录一次调用结果，true=成功 false=失败。 */
    public synchronized void record(boolean success) {
        if (size == capacity) {
            // 覆盖最老的样本前，先把它从统计中移除
            if (!samples[head]) {
                failures--;
            }
        } else {
            size++;
        }
        samples[head] = success;
        if (!success) {
            failures++;
        }
        head = (head + 1) % capacity;
    }

    /** 窗口内失败次数。 */
    public synchronized int failures() {
        return failures;
    }

    /** 窗口内总记录数（未满窗口时为实际记录条数）。 */
    public synchronized int total() {
        return size;
    }

    /** 清空窗口（熔断状态转换时调用）。 */
    public synchronized void reset() {
        head = 0;
        size = 0;
        failures = 0;
    }
}
