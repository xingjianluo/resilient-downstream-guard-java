package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 下游调用保护门面：熔断 + 隔离 + 超时 + 降级 + 重复请求保护 + 上下文传播 + 指标。
 *
 * 一次调用的处理顺序：
 * 熔断检查（打开直接降级） -> 重复请求保护（命中保留结果/在途共享/成为 leader）
 * -> 隔离仓提交（资源耗尽降级） -> 等待结果（超时降级，但不中断下游以保证副作用最多一次）。
 */
public final class DownstreamGuard {

    private static final Logger log = LoggerFactory.getLogger(DownstreamGuard.class);

    private final DownstreamRegistry registry;
    private final ContextPropagator propagator;

    public DownstreamGuard(DownstreamRegistry registry, ContextPropagator propagator) {
        this.registry = registry;
        this.propagator = propagator;
    }

    public <T> GuardResult<T> execute(String downstream, String businessKey,
                                      DownstreamCaller<T> caller) {
        CallContext explicit = propagator.capture();
        CallContext context = explicit != null ? explicit
                : new CallContext(UUID.randomUUID().toString());
        return execute(downstream, businessKey, context, caller);
    }

    public <T> GuardResult<T> execute(String downstream, String businessKey,
                                      CallContext context, DownstreamCaller<T> caller) {
        long start = System.nanoTime();
        DownstreamRuntime rt = registry.get(downstream);
        if (rt == null) {
            throw new IllegalArgumentException("downstream not registered: " + downstream);
        }
        GuardMetrics metrics = rt.metrics();
        long timeoutMillis = rt.config().getTimeout().toMillis();

        // 1. 熔断检查
        if (!rt.circuitBreaker().allowRequest()) {
            GuardResult<T> r = fallback(downstream, businessKey, context, elapsed(start),
                    FailureReason.CIRCUIT_OPEN, "circuit breaker is open", false);
            metrics.recordFallback(FailureReason.CIRCUIT_OPEN);
            log.info("[guard:{}] key='{}' rejected: CIRCUIT_OPEN, requestId={}",
                    downstream, businessKey, context.getRequestId());
            return r;
        }

        // 2. 重复请求保护
        RequestDeduplicator.DedupAttempt attempt = rt.deduplicator().begin(businessKey);
        if (attempt.isCachedHit()) {
            @SuppressWarnings("unchecked")
            GuardResult<T> cached = (GuardResult<T>) attempt.cachedResult();
            GuardResult<T> r = withEnvelope(cached, downstream, businessKey, context, elapsed(start));
            metrics.recordSuccess(true);
            log.info("[guard:{}] key='{}' reused retained result ({}), requestId={}",
                    downstream, businessKey, cached.isSuccess() ? "success" : "fallback",
                    context.getRequestId());
            return r;
        }
        if (attempt.isFollower()) {
            GuardResult<?> shared = attempt.slot().await(timeoutMillis);
            if (shared == null) {
                GuardResult<T> r = fallback(downstream, businessKey, context, elapsed(start),
                        FailureReason.TIMEOUT,
                        "timeout while waiting for in-flight duplicate call", true);
                metrics.recordFallback(FailureReason.TIMEOUT);
                log.warn("[guard:{}] follower key='{}' timed out waiting leader, requestId={}",
                        downstream, businessKey, context.getRequestId());
                return r;
            }
            @SuppressWarnings("unchecked")
            GuardResult<T> sharedT = (GuardResult<T>) shared;
            GuardResult<T> r = withEnvelope(sharedT, downstream, businessKey, context, elapsed(start));
            metrics.recordSuccess(true);
            log.info("[guard:{}] follower key='{}' shared leader result, requestId={}",
                    downstream, businessKey, context.getRequestId());
            return r;
        }

        // 3. leader：提交到该下游专属隔离仓
        InflightSlot slot = attempt.slot();
        AtomicReference<Outcome<T>> outcomeRef = new AtomicReference<>();
        CallContext captured = context;
        Runnable task = () -> {
            try {
                propagator.runWith(captured, () -> {
                    T value;
                    try {
                        value = caller.call();
                    } catch (Exception e) {
                        outcomeRef.set(Outcome.error(e));
                        return null;
                    }
                    outcomeRef.set(Outcome.ok(value));
                    return null;
                });
            } catch (Exception e) {
                outcomeRef.set(Outcome.error(e));
            }
        };

        Future<?> future = rt.bulkhead().submit(task);
        if (future == null) {
            // 隔离资源耗尽：没有执行下游，摘除在途槽并降级
            rt.deduplicator().finishLeader(businessKey, slot,
                    fallback(downstream, businessKey, context, elapsed(start),
                            FailureReason.BULKHEAD_REJECTED, "bulkhead exhausted", false));
            metrics.recordFallback(FailureReason.BULKHEAD_REJECTED);
            log.warn("[guard:{}] leader key='{}' rejected: BULKHEAD_REJECTED, requestId={}",
                    downstream, businessKey, context.getRequestId());
            @SuppressWarnings("unchecked")
            GuardResult<T> r = (GuardResult<T>) slot.await(0);
            return r;
        }

        // 4. 等待下游结果；超时仅放弃等待，不取消任务，保证副作用最多执行一次
        try {
            future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            GuardResult<T> r = fallback(downstream, businessKey, context, elapsed(start),
                    FailureReason.TIMEOUT, "downstream call timed out after " + timeoutMillis + "ms",
                    false);
            // 注意：不在此摘除在途槽；任务后台完成后会登记结果并保留，供超时重试复用
            metrics.recordFallback(FailureReason.TIMEOUT);
            log.warn("[guard:{}] leader key='{}' TIMEOUT after {}ms (task continues in background), requestId={}",
                    downstream, businessKey, timeoutMillis, context.getRequestId());
            return r;
        } catch (Exception e) {
            // 执行器自身异常（极少见），按下游错误归类
            outcomeRef.compareAndSet(null, Outcome.error(e));
        }

        Outcome<T> outcome = outcomeRef.get();
        if (outcome != null && outcome.success) {
            rt.circuitBreaker().recordSuccess();
            GuardResult<T> r = new GuardResult<>(true, false, outcome.value, null, null,
                    context.getRequestId(), downstream, businessKey, elapsed(start), false);
            metrics.recordSuccess(false);
            rt.deduplicator().finishLeader(businessKey, slot, r);
            log.info("[guard:{}] key='{}' success in {}ms, requestId={}",
                    downstream, businessKey, r.getLatencyMillis(), context.getRequestId());
            return r;
        }
        Throwable err = outcome == null ? new IllegalStateException("empty outcome") : outcome.error;
        rt.circuitBreaker().recordFailure();
        GuardResult<T> r = fallback(downstream, businessKey, context, elapsed(start),
                FailureReason.DOWNSTREAM_ERROR,
                err == null ? "downstream error" : String.valueOf(err), false);
        metrics.recordFallback(FailureReason.DOWNSTREAM_ERROR);
        rt.deduplicator().finishLeader(businessKey, slot, r);
        log.warn("[guard:{}] key='{}] DOWNSTREAM_ERROR: {}, requestId={}",
                downstream, businessKey, err, context.getRequestId());
        return r;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static <T> GuardResult<T> fallback(String downstream, String businessKey,
                                               CallContext context, long latency,
                                               FailureReason reason, String message,
                                               boolean dedupShared) {
        return new GuardResult<>(false, true, null, reason, message,
                context.getRequestId(), downstream, businessKey, latency, dedupShared);
    }

    /** 用本次调用的请求上下文与延迟包装共享/保留结果。 */
    private static <T> GuardResult<T> withEnvelope(GuardResult<T> base, String downstream,
                                                   String businessKey, CallContext context,
                                                   long latency) {
        return new GuardResult<>(base.isSuccess(), base.isFallback(), base.getValue(),
                base.getFailureReason(), base.getErrorMessage(), context.getRequestId(),
                downstream, businessKey, latency, true);
    }

    public void shutdown() {
        registry.all().forEach(DownstreamRuntime::shutdown);
    }

    /** 内部执行结果。 */
    private static final class Outcome<T> {
        final boolean success;
        final T value;
        final Throwable error;

        private Outcome(boolean success, T value, Throwable error) {
            this.success = success;
            this.value = value;
            this.error = error;
        }

        static <T> Outcome<T> ok(T value) {
            return new Outcome<>(true, value, null);
        }

        static <T> Outcome<T> error(Throwable error) {
            return new Outcome<>(false, null, error);
        }
    }
}
