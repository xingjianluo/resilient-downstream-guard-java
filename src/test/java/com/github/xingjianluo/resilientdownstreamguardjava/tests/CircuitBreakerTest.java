package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.config.CircuitBreakerConfig;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.CircuitState;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.circuit.CircuitBreaker;
import com.github.xingjianluo.resilientdownstreamguardjava.support.TestTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断器状态机测试：阈值判定、冷却期快速失败、半开受控探测与恢复关闭。
 * 时间全部由 {@link TestTimeSource} 推进，可复现。
 */
class CircuitBreakerTest {

    // 窗口 10、最少 5 次、失败率 0.5、冷却 1000ms、半开允许 3 个探测
    private CircuitBreakerConfig config = new CircuitBreakerConfig(10, 5, 0.5d, 1_000L, 3);

    @Test
    @DisplayName("冷启动样本不足时不因偶发失败打开熔断")
    void shouldNotOpenBeforeMinimumCalls() {
        TestTimeSource clock = new TestTimeSource();
        CircuitBreaker cb = new CircuitBreaker("X", config, clock);

        for (int i = 0; i < 4; i++) {
            assertTrue(cb.tryAcquirePermission().isEmpty());
            cb.recordFailure();
        }
        assertEquals(CircuitState.CLOSED, cb.currentState(), "样本不足，不应熔断");
    }

    @Test
    @DisplayName("达到失败率阈值后打开熔断，冷却期内一律快速失败")
    void shouldOpenWhenFailureRateReachedAndStayOpenDuringCooldown() {
        TestTimeSource clock = new TestTimeSource();
        CircuitBreaker cb = new CircuitBreaker("X", config, clock);

        // 5 次失败（总数 5，失败率 1.0）
        for (int i = 0; i < 5; i++) {
            assertTrue(cb.tryAcquirePermission().isEmpty());
            cb.recordFailure();
        }
        assertEquals(CircuitState.OPEN, cb.currentState());
        long openedAt = cb.snapshot().openedAtMillis();
        assertTrue(openedAt >= 0);

        // 冷却期内：未放行，且原因是 CIRCUIT_OPEN
        clock.advance(999L);
        Optional<FallbackReason> rejected = cb.tryAcquirePermission();
        assertTrue(rejected.isPresent());
        assertEquals(FallbackReason.CIRCUIT_OPEN, rejected.get());
        assertEquals(CircuitState.OPEN, cb.currentState());
    }

    @Test
    @DisplayName("冷却到期后进入半开，探测名额受控，全部成功才恢复关闭")
    void shouldTransitionToHalfOpenThenCloseAfterAllProbesSucceed() {
        TestTimeSource clock = new TestTimeSource();
        CircuitBreaker cb = new CircuitBreaker("X", config, clock);

        for (int i = 0; i < 5; i++) {
            cb.tryAcquirePermission();
            cb.recordFailure();
        }
        assertEquals(CircuitState.OPEN, cb.currentState());

        clock.advance(1_000L);
        // 第一个请求触发进入 HALF_OPEN 并占用 1 个探测名额
        assertTrue(cb.tryAcquirePermission().isEmpty());
        assertEquals(CircuitState.HALF_OPEN, cb.currentState());
        assertEquals(1, cb.snapshot().activeProbes());

        // 还能再放行 2 个探测
        assertTrue(cb.tryAcquirePermission().isEmpty());
        assertTrue(cb.tryAcquirePermission().isEmpty());
        // 第 4 个请求必须被拒绝（探测名额只有 3 个）
        Optional<FallbackReason> rejected = cb.tryAcquirePermission();
        assertTrue(rejected.isPresent());
        assertEquals(FallbackReason.HALF_OPEN_PROBE_REJECTED, rejected.get());

        // 前两次探测成功还不能关闭（需要 3 次成功）
        cb.recordSuccess();
        cb.recordSuccess();
        assertEquals(CircuitState.HALF_OPEN, cb.currentState(), "探测未全部成功，保持半开");

        cb.recordSuccess();
        assertEquals(CircuitState.CLOSED, cb.currentState(), "3 个探测全部成功，恢复关闭");
        assertEquals(0, cb.snapshot().windowTotal(), "恢复后统计窗口已清空");
    }

    @Test
    @DisplayName("半开探测只要失败一次立即重新打开并重新计时冷却")
    void shouldReopenImmediatelyWhenHalfOpenProbeFails() {
        TestTimeSource clock = new TestTimeSource();
        CircuitBreaker cb = new CircuitBreaker("X", config, clock);

        for (int i = 0; i < 5; i++) {
            cb.tryAcquirePermission();
            cb.recordFailure();
        }
        clock.advance(1_000L);
        assertTrue(cb.tryAcquirePermission().isEmpty()); // 进入半开
        assertEquals(CircuitState.HALF_OPEN, cb.currentState());

        long reopenedAt = clock.nowMillis();
        cb.recordFailure();
        assertEquals(CircuitState.OPEN, cb.currentState());
        assertEquals(reopenedAt, cb.snapshot().openedAtMillis(), "重新冷却时间应刷新");

        // 新冷却期内仍快速失败
        clock.advance(500L);
        assertEquals(FallbackReason.CIRCUIT_OPEN, cb.tryAcquirePermission().orElseThrow());
    }

    @Test
    @DisplayName("成功比例足够时不会打开熔断")
    void shouldStayClosedWhenHealthy() {
        TestTimeSource clock = new TestTimeSource();
        CircuitBreaker cb = new CircuitBreaker("X", config, clock);
        // 6 次成功 2 次失败（失败率 0.25 < 0.5）
        for (int i = 0; i < 6; i++) {
            cb.tryAcquirePermission();
            cb.recordSuccess();
        }
        for (int i = 0; i < 2; i++) {
            cb.tryAcquirePermission();
            cb.recordFailure();
        }
        assertEquals(CircuitState.CLOSED, cb.currentState());
    }
}
