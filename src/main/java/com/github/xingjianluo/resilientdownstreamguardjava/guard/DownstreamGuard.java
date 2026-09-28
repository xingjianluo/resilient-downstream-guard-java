package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.bulkhead.Bulkhead;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.circuit.CircuitBreaker;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.CircuitBreakerConfig;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.CircuitState;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.GuardRejectedException;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.SystemTimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.idempotent.IdempotentRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.metrics.GuardMetricsRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.CircuitStatus;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContextHolder;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.TracingExecutor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * 下游调用保护门面。执行顺序：
 * <ol>
 *   <li>幂等：按业务键 acquire，得到 leader（真正执行）/ follower（等待复用）/ replay（缓存重放）；</li>
 *   <li>leader：熔断许可 → 隔离舱占位（非阻塞）→ 后台执行真实下游 → 当前线程限时等待；</li>
 *   <li>超时：当前调用返回 TIMEOUT 降级，但<b>不取消</b>后台任务、不完成幂等 future，
 *       下游继续跑完，超时重试作为 follower 等待同一结果，副作用最多一次；</li>
 *   <li>结果：成功/真实失败/各类拒绝分别落到不同的 GuardResult 形态与 FallbackReason；</li>
 *   <li>指标：成功、真实失败、超时、降级、拒绝、复用分别累计，并回写熔断器统计。</li>
 * </ol>
 */
public class DownstreamGuard {

    private final GuardProperties properties;
    private final GuardMetricsRegistry metricsRegistry;
    private final ScheduledExecutorService executor;
    private final TimeSource timeSource;
    private final boolean ownsExecutor;

    private final Map<DownstreamName, CircuitBreaker> breakers = new EnumMap<>(DownstreamName.class);
    private final Map<DownstreamName, Bulkhead> bulkheads = new EnumMap<>(DownstreamName.class);
    private final IdempotentRegistry<String> idempotency;
    private final Object registerLock = new Object();

    public DownstreamGuard(GuardProperties properties, GuardMetricsRegistry metricsRegistry,
                           ScheduledExecutorService executor, TimeSource timeSource) {
        this.properties = properties;
        this.metricsRegistry = metricsRegistry;
        this.timeSource = timeSource;
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = new TracingExecutor(Executors.newScheduledThreadPool(8));
            this.ownsExecutor = true;
        }
        this.idempotency = new IdempotentRegistry<>(timeSource,
                properties.getIdempotentTtl().toMillis(), properties.getIdempotentMaxEntries());
    }

    public DownstreamGuard(GuardProperties properties) {
        this(properties, new GuardMetricsRegistry(), null, new SystemTimeSource());
    }

    /** 注册一个下游的熔断规则与隔离舱大小（重复注册以首次为准）。 */
    public void register(DownstreamName downstream, CircuitBreakerConfig circuitConfig, int bulkheadMaxConcurrent) {
        synchronized (registerLock) {
            if (breakers.containsKey(downstream)) {
                return;
            }
            CircuitBreaker breaker = new CircuitBreaker(downstream.name(), circuitConfig, timeSource);
            breakers.put(downstream, breaker);
            bulkheads.put(downstream, new Bulkhead(downstream.name(), bulkheadMaxConcurrent));
            metricsRegistry.registerCircuit(downstream.name(), breaker::snapshot);
        }
    }

    private void ensureRegistered(DownstreamName downstream) {
        if (!breakers.containsKey(downstream)) {
            register(downstream, properties.getCircuit().toConfig(), properties.getBulkheadMaxConcurrent());
        }
    }

    /** 执行一次受保护调用（无幂等键，每次都真实调用）。 */
    public GuardResult execute(DownstreamName downstream, long timeoutMillis, Supplier<String> supplier) {
        return doExecute(downstream, null, timeoutMillis, supplier);
    }

    /** 执行一次受幂等保护的调用。 */
    public GuardResult execute(DownstreamName downstream, String businessKey, long timeoutMillis,
                               Supplier<String> supplier) {
        if (businessKey == null || businessKey.isBlank()) {
            return doExecute(downstream, null, timeoutMillis, supplier);
        }
        return doExecute(downstream, businessKey, timeoutMillis, supplier);
    }

    private GuardResult doExecute(DownstreamName downstream, String businessKey, long timeoutMillis,
                                  Supplier<String> supplier) {
        ensureRegistered(downstream);
        String name = downstream.name();
        long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : properties.getDefaultTimeout().toMillis();
        long start = timeSource.nowMillis();

        if (businessKey != null) {
            IdempotentRegistry.Handle<String> handle = idempotency.acquire(businessKey);
            switch (handle.kind()) {
                case REPLAY -> {
                    metricsRegistry.recordDuplicate(name);
                    return GuardResult.success(handle.replayValue(), true, elapsed(start), context());
                }
                case FOLLOWER -> {
                    IdempotentRegistry.Await<String> await = idempotency.await(handle, effectiveTimeout);
                    if (await.succeeded()) {
                        metricsRegistry.recordDuplicate(name);
                        return GuardResult.success(await.value(), true, elapsed(start), context());
                    }
                    if (await.timedOut()) {
                        metricsRegistry.recordFallback(name, FallbackReason.IN_FLIGHT_TIMEOUT);
                        metricsRegistry.recordTimeout(name);
                        return GuardResult.fallback(FallbackReason.IN_FLIGHT_TIMEOUT, true,
                                elapsed(start), context());
                    }
                    return mapErrorToResult(name, await.error(), elapsed(start));
                }
                case LEADER -> {
                    return runLeader(downstream, businessKey, handle, effectiveTimeout, start, supplier);
                }
            }
        }
        return runDirect(downstream, effectiveTimeout, start, supplier);
    }

    private GuardResult runLeader(DownstreamName downstream, String businessKey,
                                  IdempotentRegistry.Handle<String> handle, long timeoutMillis,
                                  long start, Supplier<String> supplier) {
        String name = downstream.name();
        Optional<FallbackReason> rejection = tryAdmit(downstream);
        if (rejection.isPresent()) {
            idempotency.fail(handle, new GuardRejectedException(rejection.get()));
            metricsRegistry.recordFallback(name, rejection.get());
            return GuardResult.fallback(rejection.get(), false, elapsed(start), context());
        }
        Bulkhead bulkhead = bulkheads.get(downstream);
        CompletableFuture<String> task = CompletableFuture.supplyAsync(supplier, executor);
        try {
            String value = task.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            bulkhead.release();
            breakers.get(downstream).recordSuccess();
            metricsRegistry.recordSuccess(name);
            idempotency.complete(handle, value);
            return GuardResult.success(value, false, elapsed(start), context());
        } catch (java.util.concurrent.TimeoutException e) {
            // 不取消 task、不完成 future：后台继续执行，重试复用，副作用最多一次
            registerTimeoutCompletion(downstream, bulkhead, task, handle);
            metricsRegistry.recordTimeout(name);
            metricsRegistry.recordFallback(name, FallbackReason.TIMEOUT);
            return GuardResult.fallback(FallbackReason.TIMEOUT, false, elapsed(start), context());
        } catch (Throwable e) {
            bulkhead.release();
            Throwable cause = unwrap(e);
            breakers.get(downstream).recordFailure();
            metricsRegistry.recordFailure(name);
            idempotency.fail(handle, cause);
            return GuardResult.failure(cause.toString(), elapsed(start), context());
        }
    }

    private GuardResult runDirect(DownstreamName downstream, long timeoutMillis, long start,
                                  Supplier<String> supplier) {
        String name = downstream.name();
        Optional<FallbackReason> rejection = tryAdmit(downstream);
        if (rejection.isPresent()) {
            metricsRegistry.recordFallback(name, rejection.get());
            return GuardResult.fallback(rejection.get(), false, elapsed(start), context());
        }
        Bulkhead bulkhead = bulkheads.get(downstream);
        CompletableFuture<String> task = CompletableFuture.supplyAsync(supplier, executor);
        try {
            String value = task.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            bulkhead.release();
            breakers.get(downstream).recordSuccess();
            metricsRegistry.recordSuccess(name);
            return GuardResult.success(value, false, elapsed(start), context());
        } catch (java.util.concurrent.TimeoutException e) {
            bulkhead.release();
            metricsRegistry.recordTimeout(name);
            metricsRegistry.recordFallback(name, FallbackReason.TIMEOUT);
            task.cancel(true);
            return GuardResult.fallback(FallbackReason.TIMEOUT, false, elapsed(start), context());
        } catch (Throwable e) {
            bulkhead.release();
            Throwable cause = unwrap(e);
            breakers.get(downstream).recordFailure();
            metricsRegistry.recordFailure(name);
            return GuardResult.failure(cause.toString(), elapsed(start), context());
        }
    }

    /** leader 超时：后台任务最终结束时仍归还名额、回写统计，并在成功时完成 future 释放等待者。 */
    private void registerTimeoutCompletion(DownstreamName downstream, Bulkhead bulkhead,
                                           CompletableFuture<String> task,
                                           IdempotentRegistry.Handle<String> handle) {
        task.whenComplete((value, error) -> {
            bulkhead.release();
            CircuitBreaker breaker = breakers.get(downstream);
            if (error == null) {
                breaker.recordSuccess();
                metricsRegistry.recordSuccess(downstream.name());
                idempotency.complete(handle, value);
            } else {
                breaker.recordFailure();
                metricsRegistry.recordFailure(downstream.name());
                idempotency.fail(handle, unwrap(error));
            }
        });
    }

    /** 熔断 + 隔离的准入判断（均为非阻塞快速判定）。 */
    private Optional<FallbackReason> tryAdmit(DownstreamName downstream) {
        CircuitBreaker breaker = breakers.get(downstream);
        Optional<FallbackReason> circuitDecision = breaker.tryAcquirePermission();
        if (circuitDecision.isPresent()) {
            return circuitDecision;
        }
        if (!bulkheads.get(downstream).tryAcquire()) {
            return Optional.of(FallbackReason.BULKHEAD_REJECTED);
        }
        return Optional.empty();
    }

    private GuardResult mapErrorToResult(String name, Throwable error, long elapsed) {
        if (error instanceof GuardRejectedException gre) {
            metricsRegistry.recordFallback(name, gre.reason());
            return GuardResult.fallback(gre.reason(), true, elapsed, context());
        }
        metricsRegistry.recordFailure(name);
        return GuardResult.failure(error == null ? "unknown error" : error.toString(), elapsed, context());
    }

    private long elapsed(long start) {
        return Math.max(0L, timeSource.nowMillis() - start);
    }

    private static Map<String, String> context() {
        return RequestContextHolder.currentContextMap();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException) {
            if (cur.getCause() == null) {
                return cur;
            }
            cur = cur.getCause();
        }
        return cur;
    }

    public CircuitStatus circuitStatus(DownstreamName downstream) {
        ensureRegistered(downstream);
        return breakers.get(downstream).snapshot();
    }

    public CircuitState circuitState(DownstreamName downstream) {
        ensureRegistered(downstream);
        return breakers.get(downstream).currentState();
    }

    public int bulkheadActive(DownstreamName downstream) {
        ensureRegistered(downstream);
        return bulkheads.get(downstream).activeCount();
    }

    public int idempotencyCacheSize() {
        return idempotency.size();
    }

    public Map<String, Object> metrics(String downstream) {
        return metricsRegistry.snapshot(downstream);
    }

    public Map<String, Map<String, Object>> metricsAll() {
        return metricsRegistry.snapshotAll();
    }

    public void resetMetrics(String downstream) {
        metricsRegistry.reset(downstream);
    }
}
