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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 隔离舱测试：并发名额耗尽时快速失败（BULKHEAD_REJECTED），
 * 且单下游变慢/占满不能拖垮其它下游；调用结束后名额可恢复。
 */
class BulkheadTest {

    private GuardHandle handle;
    private ExecutorService pool;

    @AfterEach
    void tearDown() throws Exception {
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
        if (handle != null) {
            handle.shutdown();
        }
    }

    @Test
    @DisplayName("并发名额耗尽时超额请求被 BULKHEAD_REJECTED 拒绝，与真实失败区分")
    void excessRequestsRejectedWhenBulkheadFull() throws Exception {
        GuardProperties props = GuardTestFactory.properties(2, 2_000L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        pool = Executors.newFixedThreadPool(5);

        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();

        // 占满 2 个名额的慢任务
        Future<?> f1 = pool.submit(() -> runSlow(DownstreamName.INVENTORY, inside, release, entered));
        Future<?> f2 = pool.submit(() -> runSlow(DownstreamName.INVENTORY, inside, release, entered));
        assertTrue(inside.await(2, TimeUnit.SECONDS), "两个名额应被占满");
        assertEquals(2, handle.guard().bulkheadActive(DownstreamName.INVENTORY));

        // 第 3 个请求应被隔离舱直接拒绝（不触达下游）
        GuardResult rejected = handle.guard().execute(
                DownstreamName.INVENTORY, null, 2_000L,
                () -> { entered.incrementAndGet(); return "x"; });
        assertTrue(rejected.fallback());
        assertEquals(FallbackReason.BULKHEAD_REJECTED, rejected.reason());

        release.countDown();
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);

        // 名额释放后恢复正常
        GuardResult ok = handle.guard().execute(
                DownstreamName.INVENTORY, null, 2_000L, () -> "recovered");
        assertTrue(ok.success());
        assertEquals("recovered", ok.payload());
        assertEquals(0, handle.guard().bulkheadActive(DownstreamName.INVENTORY));
        assertEquals(2, entered.get(), "被拒绝的请求不应触达下游");
    }

    @Test
    @DisplayName("一个下游占满隔离舱不影响其它下游")
    void saturatedDownstreamDoesNotAffectOthers() throws Exception {
        GuardProperties props = GuardTestFactory.properties(2, 2_000L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        pool = Executors.newFixedThreadPool(5);

        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        // 打满 INVENTORY
        Future<?> f1 = pool.submit(() -> runSlow(DownstreamName.INVENTORY, inside, release, new AtomicInteger()));
        Future<?> f2 = pool.submit(() -> runSlow(DownstreamName.INVENTORY, inside, release, new AtomicInteger()));
        assertTrue(inside.await(2, TimeUnit.SECONDS));

        // POINTS 独立隔离舱，应正常成功
        GuardResult points = handle.guard().execute(
                DownstreamName.POINTS, null, 2_000L, () -> "points-ok");
        assertTrue(points.success(), "其它下游不应受影响");
        assertEquals("points-ok", points.payload());
        assertEquals(0, handle.guard().bulkheadActive(DownstreamName.POINTS));

        release.countDown();
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
    }

    private Void runSlow(DownstreamName name, CountDownLatch inside,
                         CountDownLatch release, AtomicInteger entered) {
        handle.guard().execute(name, null, 2_000L, () -> {
            entered.incrementAndGet();
            inside.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow-ok";
        });
        return null;
    }
}
