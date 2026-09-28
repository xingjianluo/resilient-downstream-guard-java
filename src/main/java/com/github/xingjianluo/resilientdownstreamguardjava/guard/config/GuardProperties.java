package com.github.xingjianluo.resilientdownstreamguardjava.guard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 守卫全局可配置项（application.properties 中以 guard.* 覆盖）。
 */
@ConfigurationProperties(prefix = "guard")
public class GuardProperties {

    /** 默认调用超时时间。 */
    private Duration defaultTimeout = Duration.ofSeconds(2);

    /** 每个下游隔离舱最大并发数。 */
    private int bulkheadMaxConcurrent = 10;

    /** 幂等结果保留时长（供超时重试/并发提交复用结果）。 */
    private Duration idempotentTtl = Duration.ofSeconds(30);

    /** 幂等结果缓存最大条目数（有界，避免长期运行堆积）。 */
    private int idempotentMaxEntries = 10_000;

    private final Circuit circuit = new Circuit();

    public Duration getDefaultTimeout() { return defaultTimeout; }
    public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }
    public int getBulkheadMaxConcurrent() { return bulkheadMaxConcurrent; }
    public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) { this.bulkheadMaxConcurrent = bulkheadMaxConcurrent; }
    public Duration getIdempotentTtl() { return idempotentTtl; }
    public void setIdempotentTtl(Duration idempotentTtl) { this.idempotentTtl = idempotentTtl; }
    public int getIdempotentMaxEntries() { return idempotentMaxEntries; }
    public void setIdempotentMaxEntries(int idempotentMaxEntries) { this.idempotentMaxEntries = idempotentMaxEntries; }
    public Circuit getCircuit() { return circuit; }

    public static class Circuit {
        private int windowSize = 20;
        private int minimumNumberOfCalls = 5;
        private double failureRateThreshold = 0.5d;
        private Duration waitInOpen = Duration.ofSeconds(5);
        private int halfOpenPermittedCalls = 3;

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }
        public double getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(double failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        public Duration getWaitInOpen() { return waitInOpen; }
        public void setWaitInOpen(Duration waitInOpen) { this.waitInOpen = waitInOpen; }
        public int getHalfOpenPermittedCalls() { return halfOpenPermittedCalls; }
        public void setHalfOpenPermittedCalls(int halfOpenPermittedCalls) { this.halfOpenPermittedCalls = halfOpenPermittedCalls; }

        public CircuitBreakerConfig toConfig() {
            return new CircuitBreakerConfig(windowSize, minimumNumberOfCalls,
                    failureRateThreshold, waitInOpen.toMillis(), halfOpenPermittedCalls);
        }
    }
}
