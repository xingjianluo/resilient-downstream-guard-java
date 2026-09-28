package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContext;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContextHolder;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory.GuardHandle;
import com.github.xingjianluo.resilientdownstreamguardjava.support.TestTimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨线程上下文传播：超时后真实调用在后台线程继续时仍可读取到同一 requestId，
 * 返回结果中携带上下文；并验证失败结果不缓存、恢复后同键可真正重试成功。
 */
class ContextPropagationTest {

    private GuardHandle handle;

    @AfterEach
    void tearDown() {
        if (handle != null) {
            handle.shutdown();
        }
        RequestContextHolder.clear();
    }

    @Test
    @DisplayName("超时后下游在后台线程执行时仍携带同一 requestId，结果上下文可关联")
    void contextPropagatesToBackgroundThreadAfterTimeout() throws Exception {
        GuardProperties props = GuardTestFactory.properties(10, 100L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        AtomicReference<String> bgRequestId = new AtomicReference<>("<none>");
        CountDownLatchCapture latch = new CountDownLatchCapture();

        RequestContext ctx = new RequestContext("req-ctx-1", "ctx-key", Map.of());
        RequestContextHolder.set(ctx);
        GuardResult timedOut;
        try {
            timedOut = handle.guard().execute(DownstreamName.POINTS, "ctx-key", 100L, () -> {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                bgRequestId.set(RequestContextHolder.currentRequestId());
                latch.done();
                return "bg-value";
            });
        } finally {
            RequestContextHolder.clear();
        }

        assertTrue(timedOut.fallback());
        assertEquals(FallbackReason.TIMEOUT, timedOut.reason());
        assertEquals("req-ctx-1", timedOut.context().get("requestId"),
                "返回结果需携带发起请求的上下文");

        latch.await();
        assertEquals("req-ctx-1", bgRequestId.get(),
                "后台线程中也必须能关联到同一请求");
    }

    @Test
    @DisplayName("失败结果不缓存：同键首次失败后，下游恢复时重试应真正再执行并成功")
    void failedResultNotCachedAndRetryReallyExecutes() {
        GuardProperties props = GuardTestFactory.properties(10, 1_000L, 60_000L, 1000);
        handle = GuardTestFactory.create(props, new TestTimeSource());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        GuardResult first = handle.guard().execute(DownstreamName.PROFILE, "flaky-key", 1_000L, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary error");
            }
            return "recovered-value";
        });
        assertTrue(first.failure(), "首次为真实失败");
        assertEquals(1, calls.get());

        GuardResult second = handle.guard().execute(DownstreamName.PROFILE, "flaky-key", 1_000L, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary error");
            }
            return "recovered-value";
        });
        assertTrue(second.success(), "失败不缓存，重试真正执行并成功");
        assertEquals("recovered-value", second.payload());
        assertFalse(second.duplicate());
        assertEquals(2, calls.get());
    }

    private static final class CountDownLatchCapture {
        private final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);

        void done() {
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
