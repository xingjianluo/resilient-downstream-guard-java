package com.github.xingjianluo.resilientdownstreamguardjava.guard.idempotent;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按业务键做并发去重与超时重试保护（泛型承载成功结果类型）。
 * <p>语义：
 * <ul>
 *   <li>同键并发提交：{@link #acquire} 只有一个调用方得到 leader 句柄并负责真正执行下游；
 *       其余得到 follower 句柄，等待并复用同一个 future 的结果；</li>
 *   <li>leader 超时放弃时<b>不</b>完成 future，下游在后台继续，重试仍作为 follower 等待同一结果，
 *       因此下游副作用最多一次，也不会漏处理；</li>
 *   <li>leader 成功后 {@link #complete}：结果在 TTL 内对后续请求直接重放（标记 replay）；</li>
 *   <li>leader 失败后 {@link #fail}：异常不缓存，条目移除，后续重试可真正再执行一次；</li>
 *   <li>缓存有界：惰性淘汰过期条目，超容量时淘汰最早完成的条目，在途条目不淘汰。</li>
 * </ul>
 * 冷却/TTL 判定走可控 {@link TimeSource}；等待 future 用真实纳秒计时，避免测试时钟导致永久阻塞。
 */
public final class IdempotentRegistry<T> {

    private final TimeSource timeSource;
    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentHashMap<String, InFlight<T>> entries = new ConcurrentHashMap<>();

    public IdempotentRegistry(TimeSource timeSource, long ttlMillis, int maxEntries) {
        this.timeSource = timeSource;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    /**
     * 获取一个业务键的执行句柄。
     * @return leader（新键）、follower（在途）或 replay（TTL 内成功结果可直接复用）
     */
    public Handle<T> acquire(String businessKey) {
        evictExpired();
        InFlight<T> created = new InFlight<>(businessKey, timeSource.nowMillis());
        while (true) {
            InFlight<T> existing = entries.putIfAbsent(businessKey, created);
            if (existing == null) {
                if (entries.size() > maxEntries) {
                    // 超容量：尝试淘汰最早完成条目后继续保留自己
                    entries.remove(businessKey, created);
                    evictOldestCompleted();
                    continue;
                }
                return new Handle<>(created, Kind.LEADER, null);
            }
            Optional<T> cached = existing.cachedResultIfFresh(timeSource.nowMillis(), ttlMillis);
            if (cached.isPresent()) {
                return new Handle<>(existing, Kind.REPLAY, cached.get());
            }
            return new Handle<>(existing, Kind.FOLLOWER, null);
        }
    }

    /** leader 成功完成：保留条目供 TTL 内重放。 */
    public void complete(Handle<T> handle, T value) {
        handle.inFlight.complete(value, timeSource.nowMillis());
    }

    /** leader 失败：异常结果不复用，移除条目以便后续真正重试。 */
    public void fail(Handle<T> handle, Throwable error) {
        InFlight<T> inFlight = handle.inFlight;
        inFlight.completeExceptionally(error, timeSource.nowMillis());
        entries.remove(inFlight.businessKey(), inFlight);
    }

    /**
     * follower 等待在途结果，使用真实纳秒超时。
     * @return 成功返回值；超时返回 {@link AwaitTimeout}；异常返回 {@link AwaitError}
     */
    public Await<T> await(Handle<T> handle, long waitMillis) {
        CompletableFuture<T> future = handle.inFlight.future();
        try {
            T value = future.get(waitMillis, TimeUnit.MILLISECONDS);
            return new Await<>(value, null, false);
        } catch (TimeoutException e) {
            return new Await<>(null, null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Await<>(null, e, false);
        } catch (ExecutionException e) {
            return new Await<>(null, unwrap(e.getCause()), false);
        }
    }

    /** 释放等待者，用于不经过本注册表执行路径时补完成。 */
    public void completeExternal(String businessKey, T value) {
        InFlight<T> inFlight = entries.get(businessKey);
        if (inFlight != null) {
            inFlight.complete(value, timeSource.nowMillis());
        }
    }

    public int size() {
        return entries.size();
    }

    public void evictExpired() {
        long now = timeSource.nowMillis();
        for (Map.Entry<String, InFlight<T>> e : entries.entrySet()) {
            InFlight<T> v = e.getValue();
            if (v.isCompleted() && v.isExpired(now, ttlMillis)) {
                entries.remove(e.getKey(), v);
            }
        }
    }

    private void evictOldestCompleted() {
        entries.values().stream()
                .filter(InFlight::isCompleted)
                .min(Comparator.comparingLong(InFlight::completedAtMillis))
                .ifPresent(old -> entries.remove(old.businessKey(), old));
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    public enum Kind { LEADER, FOLLOWER, REPLAY }

    /** acquire 返回的执行句柄。replay 时 replayValue 为可直接复用的成功结果。 */
    public static final class Handle<T> {
        final InFlight<T> inFlight;
        private final Kind kind;
        private final T replayValue;

        Handle(InFlight<T> inFlight, Kind kind, T replayValue) {
            this.inFlight = inFlight;
            this.kind = kind;
            this.replayValue = replayValue;
        }

        public Kind kind() { return kind; }
        public boolean isLeader() { return kind == Kind.LEADER; }
        public boolean isFollower() { return kind == Kind.FOLLOWER; }
        public boolean isReplay() { return kind == Kind.REPLAY; }
        public T replayValue() { return replayValue; }
    }

    /** await 的结果：value 成功；timedOut 等待超时；否则 error 持有真实异常。 */
    public record Await<T>(T value, Throwable error, boolean timedOut) {
        public boolean succeeded() { return error == null && !timedOut; }
    }
}
