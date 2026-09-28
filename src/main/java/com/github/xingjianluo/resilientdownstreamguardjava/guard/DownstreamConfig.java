package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import java.time.Duration;

/**
 * 单个下游的保护配置：熔断统计规则、冷却/半开探测、隔离仓、超时、去重缓存。
 */
public final class DownstreamConfig {

    private final String name;
    private final int maxConcurrent;
    private final int queueCapacity;
    private final Duration timeout;
    private final int windowSize;
    private final int minimumCalls;
    private final double failureRateThreshold;
    private final Duration openStateCooldown;
    private final int halfOpenMaxProbes;
    private final int halfOpenSuccessToClose;
    private final Duration dedupRetention;
    private final int dedupMaxEntries;

    public DownstreamConfig(String name, int maxConcurrent, int queueCapacity, Duration timeout,
                            int windowSize, int minimumCalls, double failureRateThreshold,
                            Duration openStateCooldown, int halfOpenMaxProbes,
                            int halfOpenSuccessToClose, Duration dedupRetention, int dedupMaxEntries) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must be non-negative");
        }
        if (windowSize <= 0 || minimumCalls <= 0 || minimumCalls > windowSize) {
            throw new IllegalArgumentException("require 0 < minimumCalls <= windowSize");
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException("failureRateThreshold must be within (0,1]");
        }
        if (halfOpenMaxProbes <= 0 || halfOpenSuccessToClose <= 0
                || halfOpenSuccessToClose > halfOpenMaxProbes) {
            throw new IllegalArgumentException("require 0 < successToClose <= maxProbes");
        }
        this.name = name;
        this.maxConcurrent = maxConcurrent;
        this.queueCapacity = queueCapacity;
        this.timeout = timeout;
        this.windowSize = windowSize;
        this.minimumCalls = minimumCalls;
        this.failureRateThreshold = failureRateThreshold;
        this.openStateCooldown = openStateCooldown;
        this.halfOpenMaxProbes = halfOpenMaxProbes;
        this.halfOpenSuccessToClose = halfOpenSuccessToClose;
        this.dedupRetention = dedupRetention;
        this.dedupMaxEntries = dedupMaxEntries;
    }

    public String getName() { return name; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public int getQueueCapacity() { return queueCapacity; }
    public Duration getTimeout() { return timeout; }
    public int getWindowSize() { return windowSize; }
    public int getMinimumCalls() { return minimumCalls; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public Duration getOpenStateCooldown() { return openStateCooldown; }
    public int getHalfOpenMaxProbes() { return halfOpenMaxProbes; }
    public int getHalfOpenSuccessToClose() { return halfOpenSuccessToClose; }
    public Duration getDedupRetention() { return dedupRetention; }
    public int getDedupMaxEntries() { return dedupMaxEntries; }

    /** 便捷构造器，未设置项使用一组合理默认值。 */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private int maxConcurrent = 8;
        private int queueCapacity = 4;
        private Duration timeout = Duration.ofMillis(500);
        private int windowSize = 20;
        private int minimumCalls = 5;
        private double failureRateThreshold = 0.5d;
        private Duration openStateCooldown = Duration.ofSeconds(2);
        private int halfOpenMaxProbes = 2;
        private int halfOpenSuccessToClose = 2;
        private Duration dedupRetention = Duration.ofSeconds(3);
        private int dedupMaxEntries = 1024;

        private Builder(String name) {
            this.name = name;
        }

        public Builder maxConcurrent(int v) { this.maxConcurrent = v; return this; }
        public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder windowSize(int v) { this.windowSize = v; return this; }
        public Builder minimumCalls(int v) { this.minimumCalls = v; return this; }
        public Builder failureRateThreshold(double v) { this.failureRateThreshold = v; return this; }
        public Builder openStateCooldown(Duration v) { this.openStateCooldown = v; return this; }
        public Builder halfOpenMaxProbes(int v) { this.halfOpenMaxProbes = v; return this; }
        public Builder halfOpenSuccessToClose(int v) { this.halfOpenSuccessToClose = v; return this; }
        public Builder dedupRetention(Duration v) { this.dedupRetention = v; return this; }
        public Builder dedupMaxEntries(int v) { this.dedupMaxEntries = v; return this; }

        public DownstreamConfig build() {
            return new DownstreamConfig(name, maxConcurrent, queueCapacity, timeout, windowSize,
                    minimumCalls, failureRateThreshold, openStateCooldown, halfOpenMaxProbes,
                    halfOpenSuccessToClose, dedupRetention, dedupMaxEntries);
        }
    }
}
