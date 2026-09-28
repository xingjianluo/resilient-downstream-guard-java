package com.github.xingjianluo.resilientdownstreamguardjava.support;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.CircuitBreakerConfig;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.metrics.GuardMetricsRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.TracingExecutor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 测试内构造独立守卫的工具。 */
public final class GuardTestFactory {

    private GuardTestFactory() {
    }

    public static GuardProperties properties(int bulkhead, long timeoutMillis,
                                             long idempotentTtlMillis, int idempotentMax) {
        GuardProperties p = new GuardProperties();
        p.setBulkheadMaxConcurrent(bulkhead);
        p.setDefaultTimeout(Duration.ofMillis(timeoutMillis));
        p.setIdempotentTtl(Duration.ofMillis(idempotentTtlMillis));
        p.setIdempotentMaxEntries(idempotentMax);
        return p;
    }

    public static GuardProperties circuitProperties(int windowSize, int minCalls,
                                                    double failureRate, long waitOpenMillis,
                                                    int halfOpenPermitted) {
        GuardProperties p = properties(10, 500L, 60_000L, 10_000);
        p.getCircuit().setWindowSize(windowSize);
        p.getCircuit().setMinimumNumberOfCalls(minCalls);
        p.getCircuit().setFailureRateThreshold(failureRate);
        p.getCircuit().setWaitInOpen(Duration.ofMillis(waitOpenMillis));
        p.getCircuit().setHalfOpenPermittedCalls(halfOpenPermitted);
        return p;
    }

    /** 构造守卫并注册指定下游；返回的 ScheduledExecutorService 在用例结束后应关闭。 */
    public static GuardHandle create(GuardProperties properties, TimeSource timeSource) {
        ScheduledExecutorService executor =
                new TracingExecutor(Executors.newScheduledThreadPool(8));
        GuardMetricsRegistry registry = new GuardMetricsRegistry();
        DownstreamGuard guard = new DownstreamGuard(properties, registry, executor, timeSource);
        for (DownstreamName name : DownstreamName.values()) {
            guard.register(name, properties.getCircuit().toConfig(), properties.getBulkheadMaxConcurrent());
        }
        return new GuardHandle(guard, registry, executor);
    }

    public record GuardHandle(DownstreamGuard guard, GuardMetricsRegistry registry,
                              ScheduledExecutorService executor) {
        public void shutdown() {
            executor.shutdownNow();
        }
    }
}
