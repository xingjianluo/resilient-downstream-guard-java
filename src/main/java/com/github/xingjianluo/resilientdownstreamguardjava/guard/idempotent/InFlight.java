package com.github.xingjianluo.resilientdownstreamguardjava.guard.idempotent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 幂等注册表中“业务键 -&gt; 在途/已完成调用”的条目。
 * 首个请求（leader）负责完成 future，follower 等待并复用，保证下游副作用最多执行一次。
 * 类型参数 T 为成功结果类型；失败以异常形式完成（不缓存，允许后续真正重试）。
 */
public final class InFlight<T> {

    private final String businessKey;
    private final CompletableFuture<T> future;
    private final long createdAtMillis;
    private volatile long completedAtMillis = -1L;

    public InFlight(String businessKey, long nowMillis) {
        this.businessKey = businessKey;
        this.future = new CompletableFuture<>();
        this.createdAtMillis = nowMillis;
    }

    public String businessKey() {
        return businessKey;
    }

    public CompletableFuture<T> future() {
        return future;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long completedAtMillis() {
        return completedAtMillis;
    }

    public boolean isCompleted() {
        return future.isDone();
    }

    public void complete(T value, long nowMillis) {
        completedAtMillis = nowMillis;
        future.complete(value);
    }

    public void completeExceptionally(Throwable error, long nowMillis) {
        completedAtMillis = nowMillis;
        future.completeExceptionally(error);
    }

    /** 已成功完成且结果仍在 TTL 内时返回缓存值。 */
    public Optional<T> cachedResultIfFresh(long now, long ttlMillis) {
        if (future.isDone() && !future.isCompletedExceptionally() && completedAtMillis >= 0
                && now - completedAtMillis < ttlMillis) {
            try {
                return Optional.ofNullable(future.getNow(null));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 已完成（成功或失败）且超过 TTL。 */
    public boolean isExpired(long now, long ttlMillis) {
        return completedAtMillis >= 0 && now - completedAtMillis >= ttlMillis;
    }
}
