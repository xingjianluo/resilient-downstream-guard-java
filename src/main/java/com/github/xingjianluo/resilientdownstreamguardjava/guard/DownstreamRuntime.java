package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 单个下游运行期持有的全部保护组件：熔断器、隔离仓、去重器、指标。
 */
public final class DownstreamRuntime {

    private final DownstreamConfig config;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final RequestDeduplicator deduplicator;
    private final GuardMetrics metrics;

    public DownstreamRuntime(DownstreamConfig config, Clock clock) {
        this.config = config;
        this.metrics = new GuardMetrics(config.getName());
        this.circuitBreaker = new CircuitBreaker(config.getName(), config, clock, metrics);
        this.bulkhead = new Bulkhead(config.getName(), config);
        this.deduplicator = new RequestDeduplicator(clock,
                config.getDedupRetention().toMillis(), config.getDedupMaxEntries());
    }

    public DownstreamConfig config() {
        return config;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public Bulkhead bulkhead() {
        return bulkhead;
    }

    public RequestDeduplicator deduplicator() {
        return deduplicator;
    }

    public GuardMetrics metrics() {
        return metrics;
    }

    public void shutdown() {
        bulkhead.shutdown();
    }
}
