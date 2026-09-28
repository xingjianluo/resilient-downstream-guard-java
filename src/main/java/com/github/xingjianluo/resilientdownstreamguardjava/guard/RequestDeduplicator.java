package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 重复请求保护（按下游维度独立实例）。
 *
 * 语义：
 * - 同 businessKey 并发提交：仅第一个请求（leader）实际执行一次下游，
 *   其余请求（follower）阻塞等待并共享同一结果，各自标记 dedupShared；
 * - leader 即使因超时放弃等待，下游仍在隔离线程中继续运行（不会被中断，保证最多一次），
 *   其完成结果在 retentionMillis 窗口内保留；该窗口内的超时重试直接复用保留结果，
 *   不会再次执行下游，从而"超时重试不重复扣减"；
 * - 超过保留窗口后同键再来，视为新的一次请求。
 *
 * 在途表与保留缓存均有界：在途键随完成被移除，保留缓存受 TTL 与 LRU 容量双重约束。
 */
public final class RequestDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(RequestDeduplicator.class);

    private final Clock clock;
    private final long retentionMillis;
    private final java.util.HashMap<String, InflightSlot> inflight = new java.util.HashMap<>();
    private final BoundedTtlCache<String, GuardResult<?>> retained;

    public RequestDeduplicator(Clock clock, long retentionMillis, int maxEntries) {
        this.clock = clock;
        this.retentionMillis = retentionMillis;
        this.retained = new BoundedTtlCache<>(maxEntries, retentionMillis, clock);
    }

    /**
     * 尝试进入一个业务键。三种结果：
     * 1) 保留窗口内已有同键的完成结果 -> cached（直接复用，不执行下游）；
     * 2) 已有在途调用 -> follower（等待共享结果）；
     * 3) 否则 -> leader（本次负责实际执行下游）。
     */
    public synchronized DedupAttempt begin(String businessKey) {
        GuardResult<?> cached = retained.getIfFresh(businessKey);
        if (cached != null) {
            log.debug("[dedup] key='{}' hit retained result within {}ms, reuse",
                    businessKey, retentionMillis);
            return DedupAttempt.cached(cached);
        }
        InflightSlot existing = inflight.get(businessKey);
        if (existing != null) {
            log.debug("[dedup] key='{}' joins inflight slot as follower", businessKey);
            return DedupAttempt.follower(existing);
        }
        InflightSlot slot = new InflightSlot();
        inflight.put(businessKey, slot);
        log.debug("[dedup] key='{}' becomes leader", businessKey);
        return DedupAttempt.leader(slot);
    }

    /**
     * leader 执行结束后调用：登记结果、唤醒 follower、转入保留缓存、移除在途表。
     */
    public synchronized void finishLeader(String businessKey, InflightSlot slot, GuardResult<?> result) {
        InflightSlot current = inflight.get(businessKey);
        // 仅当槽仍属于本次调用时摘除，避免极端情况下误删后来者的槽
        if (current == slot) {
            inflight.remove(businessKey);
        }
        retained.put(businessKey, result);
        log.debug("[dedup] key='{}' leader finished ({}), retained for {}ms",
                businessKey, result.isSuccess() ? "success" : "fallback", retentionMillis);
        slot.complete(result);
    }

    public synchronized int inflightCount() {
        return inflight.size();
    }

    public int retainedCount() {
        return retained.size();
    }

    /** 主动清理过期保留项（可周期性调用）。 */
    public void purgeExpired() {
        retained.purgeExpired();
    }

    public synchronized void clear() {
        inflight.clear();
        retained.clear();
    }

    /** 一次去重进入尝试的结果。 */
    public static final class DedupAttempt {
        private final boolean leader;
        private final InflightSlot slot;
        private final GuardResult<?> cachedResult;

        private DedupAttempt(boolean leader, InflightSlot slot, GuardResult<?> cachedResult) {
            this.leader = leader;
            this.slot = slot;
            this.cachedResult = cachedResult;
        }

        static DedupAttempt leader(InflightSlot slot) {
            return new DedupAttempt(true, slot, null);
        }

        static DedupAttempt follower(InflightSlot slot) {
            return new DedupAttempt(false, slot, null);
        }

        static DedupAttempt cached(GuardResult<?> result) {
            return new DedupAttempt(false, null, result);
        }

        public boolean isLeader() {
            return leader;
        }

        public boolean isFollower() {
            return !leader && slot != null;
        }

        public boolean isCachedHit() {
            return cachedResult != null;
        }

        public InflightSlot slot() {
            return slot;
        }

        public GuardResult<?> cachedResult() {
            return cachedResult;
        }
    }
}
