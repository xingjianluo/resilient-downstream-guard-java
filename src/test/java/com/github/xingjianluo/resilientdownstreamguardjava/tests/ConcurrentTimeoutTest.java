package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory.GuardHandle;
import com.github.xingjianluo.resilientdownstreamguardjava.support.TestTimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发超时场景：
 * 1) 下游慢于超时时返回 TIMEOUT 降级，且不与真实失败混淆；
 * 2) 同键超时重试不重复触发下游，重试可在下游完成后拿到同一结果；
 * 3) 并发提交同键时下游副作用只执行一次。
 */
class ConcurrentTimeoutTest {

    private GuardHandle handle;

    @AfterEach
    void tearDown() {
        if (handle != null) {
            handle.shutdown();
        }
    }

    @Test
    @DisplayName("慢调用触发超时降级，超时与真实失败可区分")
    void slowDownstreamReturnsTimeoutFallback() {
        GuardProperties props = GuardTestFactory.properties(10, 200L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        AtomicInteger sideEffects = new AtomicInteger();

        GuardResult r = handle.guard().execute(DownstreamName.POINTS, null, 200L, () -> {
            sideEffects.incrementAndGet();
            sleep(500L);
            return "ok";
        });

        assertTrue(r.fallback(), "超时应走降级分支");
        assertEquals(FallbackReason.TIMEOUT, r.reason());
        assertEquals(1, sideEffects.get());
    }

    @Test
    @DisplayName("超时后重试复用同一在途结果，下游只执行一次，重试最终成功")
    void timeoutRetryReusesInFlightResultAndSideEffectOnce() throws Exception {
        GuardProperties props = GuardTestFactory.properties(10, 150L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        AtomicInteger sideEffects = new AtomicInteger();

        // 同键的第一次调用：leader 超时放弃，但后台继续
        GuardResult first = handle.guard().execute(DownstreamName.POINTS, "order-1", 150L, () -> {
            sleep(400L);
            sideEffects.incrementAndGet();
            return "deducted";
        });
        assertTrue(first.fallback());
        assertEquals(FallbackReason.TIMEOUT, first.reason());

        // 立即重试：作为 follower 同样可能等不到（给 100ms）
        GuardResult retrySoon = handle.guard().execute(DownstreamName.POINTS, "order-1", 100L, () -> {
            sideEffects.incrementAndGet(); // 不应被执行
            return "NEVER";
        });
        assertTrue(retrySoon.fallback());
        assertEquals(FallbackReason.IN_FLIGHT_TIMEOUT, retrySoon.reason(), "等待在途结果超时");
        assertTrue(retrySoon.duplicate(), "重试是复用请求，应标记 duplicate");

        // 等待下游在后台完成后再次重试：应拿到同一成功结果，且标记 duplicate
        Thread.sleep(400L);
        GuardResult retryAfter = handle.guard().execute(DownstreamName.POINTS, "order-1", 200L, () -> {
            sideEffects.incrementAndGet(); // 不应被执行
            return "NEVER";
        });
        assertTrue(retryAfter.success(), "下游已完成，重试应复用成功结果");
        assertEquals("deducted", retryAfter.payload());
        assertTrue(retryAfter.duplicate());
        assertEquals(1, sideEffects.get(), "下游副作用只能执行一次");
    }

    @Test
    @DisplayName("同键并发提交只有 leader 真正执行下游，所有调用方结果一致")
    void concurrentSameKeyExecutesDownstreamOnce() throws Exception {
        GuardProperties props = GuardTestFactory.properties(10, 2_000L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        AtomicInteger sideEffects = new AtomicInteger();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        GuardResult r = handle.guard().execute(
                                DownstreamName.INVENTORY, "stock-key-1", 2_000L, () -> {
                                    sideEffects.incrementAndGet();
                                    sleep(200L);
                                    return "stock-ok";
                                });
                        if (r.success()) {
                            success.incrementAndGet();
                            if (r.duplicate()) {
                                duplicates.incrementAndGet();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, success.get(), "所有并发调用都应成功");
        assertEquals(1, sideEffects.get(), "下游副作用只能执行一次");
        assertEquals(threads - 1, duplicates.get(), "除 leader 外都应是复用结果");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
