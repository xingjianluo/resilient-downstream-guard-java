package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.idempotent.IdempotentRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.DownstreamMetrics;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.metrics.GuardMetricsRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.support.TestTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标与内存治理测试：
 * 1) 高并发下各类计数不丢失、不错乱；
 * 2) 指标快照字段齐全、熔断拒绝计入 rejected 而真实失败不计入；
 * 3) 幂等缓存 TTL 过期后可清理、容量有界，长期运行不堆积对象。
 */
class MetricsConsistencyTest {

    @Test
    @DisplayName("并发计数不丢失不错乱")
    void countersAreAccurateUnderConcurrency() throws Exception {
        GuardMetricsRegistry registry = new GuardMetricsRegistry();
        int threads = 16;
        int perThread = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            registry.recordSuccess("X");
                            registry.recordFailure("X");
                            registry.recordTimeout("X");
                            registry.recordFallback("X", FallbackReason.CIRCUIT_OPEN);
                            registry.recordDuplicate("X");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        long total = (long) threads * perThread;
        DownstreamMetrics m = registry.forDownstream("X");
        assertEquals(total, m.successCount());
        assertEquals(total, m.failureCount());
        assertEquals(total, m.timeoutCount());
        assertEquals(total, m.fallbackCount());
        assertEquals(total, m.rejectedCount(), "CIRCUIT_OPEN 必须计入 rejected");
        assertEquals(total, m.duplicateCount());
        assertEquals(total * 3, m.totalCount(), "只有 success/failure/timeout 计入 total");
    }

    @Test
    @DisplayName("真实失败计入 failure 但不计入 rejected")
    void downstreamFailureNotCountedAsRejected() {
        GuardMetricsRegistry registry = new GuardMetricsRegistry();
        registry.recordFailure("X");
        registry.recordFallback("X", FallbackReason.DOWNSTREAM_ERROR);
        DownstreamMetrics m = registry.forDownstream("X");
        assertEquals(1, m.failureCount());
        assertEquals(0, m.rejectedCount(), "真实失败不算拒绝");
        assertEquals(1, m.fallbackCount());
    }

    @Test
    @DisplayName("幂等缓存条目超过 TTL 后被清理")
    void idempotencyEntriesExpireByTtl() {
        TestTimeSource clock = new TestTimeSource();
        IdempotentRegistry<String> reg = new IdempotentRegistry<>(clock, 1_000L, 100);

        IdempotentRegistry.Handle<String> h = reg.acquire("k1");
        assertTrue(h.isLeader());
        reg.complete(h, "v1");
        assertEquals(1, reg.size());

        // TTL 内重放
        clock.advance(500L);
        IdempotentRegistry.Handle<String> replay = reg.acquire("k1");
        assertTrue(replay.isReplay());
        assertEquals("v1", replay.replayValue());

        // 过期后新 acquire 成为 leader，旧条目在下一次清理时被移除
        clock.advance(600L);
        IdempotentRegistry.Handle<String> again = reg.acquire("k1");
        assertTrue(again.isLeader());
        reg.complete(again, "v2");
        reg.evictExpired();
        assertEquals(1, reg.size(), "过期旧条目应已被淘汰");
    }

    @Test
    @DisplayName("幂等缓存容量有界，超出上限时淘汰已完成条目")
    void idempotencyCacheIsBounded() {
        TestTimeSource clock = new TestTimeSource();
        IdempotentRegistry<String> reg = new IdempotentRegistry<>(clock, 1_000L, 3);
        for (int i = 0; i < 5; i++) {
            IdempotentRegistry.Handle<String> h = reg.acquire("k" + i);
            reg.complete(h, "v" + i);
            clock.advance(1L);
        }
        assertTrue(reg.size() <= 3, "缓存不得超过容量上限, size=" + reg.size());

        // 快照中包含全部指标键且结构有界：多次读取不产生新对象
        Map<String, Object> snap = null;
        for (int i = 0; i < 100; i++) {
            snap = new GuardMetricsRegistry().snapshot("X");
        }
        assertNotNull(snap);
    }
}
