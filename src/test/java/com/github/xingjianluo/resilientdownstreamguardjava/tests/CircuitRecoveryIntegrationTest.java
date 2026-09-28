package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.GuardProperties;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.CircuitState;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory;
import com.github.xingjianluo.resilientdownstreamguardjava.support.GuardTestFactory.GuardHandle;
import com.github.xingjianluo.resilientdownstreamguardjava.support.TestTimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过守卫门面验证熔断生命周期（时间由可控时钟驱动，确定可复现）：
 * 持续真实失败 -&gt; 熔断打开（CIRCUIT_OPEN 快速失败）-&gt; 冷却到期 -&gt; 半开探测
 * -&gt; 探测成功恢复 CLOSED；并验证打开熔断时下游不被调用、其它下游不受影响。
 */
class CircuitRecoveryIntegrationTest {

    private GuardHandle handle;
    private TestTimeSource clock;

    @AfterEach
    void tearDown() {
        if (handle != null) {
            handle.shutdown();
        }
    }

    private void setup() {
        // 窗口 5、最少 5、失败率 1.0、冷却 3000ms、半开探测 1 个、隔离舱 10
        GuardProperties props = GuardTestFactory.circuitProperties(5, 5, 1.0d, 3_000L, 1);
        clock = new TestTimeSource();
        handle = GuardTestFactory.create(props, clock);
    }

    private GuardResult callFailing(DownstreamName name, AtomicInteger calls) {
        return handle.guard().execute(name, null, 1_000L, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
    }

    @Test
    @DisplayName("持续失败后熔断打开，OPEN 期快速失败且不调用下游，冷却后探测成功恢复")
    void openThenHalfOpenProbeThenClose() {
        setup();
        AtomicInteger calls = new AtomicInteger();

        // 5 次真实失败
        for (int i = 0; i < 5; i++) {
            GuardResult r = callFailing(DownstreamName.INVENTORY, calls);
            assertTrue(r.failure(), "熔断打开前应表现为真实失败");
        }
        assertEquals(CircuitState.OPEN, handle.guard().circuitState(DownstreamName.INVENTORY));
        assertEquals(5, calls.get());

        // 冷却期内：快速失败，原因为 CIRCUIT_OPEN，下游不再被调用
        for (int i = 0; i < 3; i++) {
            clock.advance(500L);
            GuardResult r = callFailing(DownstreamName.INVENTORY, calls);
            assertTrue(r.fallback());
            assertEquals(FallbackReason.CIRCUIT_OPEN, r.reason());
        }
        assertEquals(5, calls.get(), "熔断打开期间不得触达下游");

        // 下游恢复健康
        clock.advance(2_000L); // 累计超过冷却
        assertEquals(CircuitState.OPEN, handle.guard().circuitState(DownstreamName.INVENTORY));
        GuardResult probe = handle.guard().execute(
                DownstreamName.INVENTORY, null, 1_000L, () -> {
                    calls.incrementAndGet();
                    return "healthy";
                });
        // 半开只允许 1 个探测：探测成功即在同一次调用内恢复 CLOSED（中间态 HALF_OPEN 已由
        // CircuitBreakerTest 覆盖）
        assertTrue(probe.success(), "唯一探测成功后应恢复关闭");
        assertEquals("healthy", probe.payload());
        assertEquals(CircuitState.CLOSED, handle.guard().circuitState(DownstreamName.INVENTORY));
        assertEquals(6, calls.get());

        // 恢复后正常调用
        GuardResult again = handle.guard().execute(
                DownstreamName.INVENTORY, null, 1_000L, () -> {
                    calls.incrementAndGet();
                    return "healthy-2";
                });
        assertTrue(again.success());
    }

    @Test
    @DisplayName("半开探测失败立即重新打开熔断")
    void failedProbeReopens() {
        setup();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            callFailing(DownstreamName.POINTS, calls);
        }
        assertEquals(CircuitState.OPEN, handle.guard().circuitState(DownstreamName.POINTS));

        clock.advance(3_000L);
        GuardResult probe = callFailing(DownstreamName.POINTS, calls);
        assertEquals(CircuitState.OPEN, handle.guard().circuitState(DownstreamName.POINTS),
                "探测失败必须立刻重新打开");
        assertTrue(probe.failure(), "探测请求真实触达下游并失败");
        assertEquals(6, calls.get());

        // 冷却重新计时
        assertEquals(FallbackReason.CIRCUIT_OPEN,
                callFailing(DownstreamName.POINTS, calls).reason());
    }

    @Test
    @DisplayName("一个下游熔断不影响其它下游")
    void openBreakerDoesNotAffectOtherDownstream() {
        setup();
        AtomicInteger invCalls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            callFailing(DownstreamName.INVENTORY, invCalls);
        }
        assertEquals(CircuitState.OPEN, handle.guard().circuitState(DownstreamName.INVENTORY));

        GuardResult profile = handle.guard().execute(
                DownstreamName.PROFILE, null, 1_000L, () -> "profile-ok");
        assertTrue(profile.success());
        assertEquals("profile-ok", profile.payload());
        assertEquals(CircuitState.CLOSED, handle.guard().circuitState(DownstreamName.PROFILE));
    }
}
