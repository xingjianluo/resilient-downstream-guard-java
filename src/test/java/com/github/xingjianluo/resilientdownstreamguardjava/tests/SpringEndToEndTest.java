package com.github.xingjianluo.resilientdownstreamguardjava.tests;

import com.github.xingjianluo.resilientdownstreamguardjava.downstream.BehaviorSpec;
import com.github.xingjianluo.resilientdownstreamguardjava.downstream.BusinessService;
import com.github.xingjianluo.resilientdownstreamguardjava.downstream.DownstreamService;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 装配后的端到端验证：本地模拟下游可注入故障/恢复，守卫返回可区分结局，指标可查询。
 */
@SpringBootTest(properties = {
        "guard.default-timeout=500ms",
        "guard.circuit.minimum-number-of-calls=3",
        "guard.circuit.failure-rate-threshold=1.0",
        "guard.circuit.wait-in-open=300ms",
        "guard.circuit.half-open-permitted-calls=1"
})
class SpringEndToEndTest {

    @Autowired
    BusinessService businessService;
    @Autowired
    DownstreamGuard guard;
    @Autowired
    Map<DownstreamName, DownstreamService> downstreams;

    @AfterEach
    void reset() {
        for (DownstreamService s : downstreams.values()) {
            s.setBehavior(BehaviorSpec.ok(5L));
            s.resetInvocationCount();
        }
        for (DownstreamName n : DownstreamName.values()) {
            guard.resetMetrics(n.name());
        }
    }

    private GuardResult call(DownstreamName name, String key, long timeout) {
        RequestContext ctx = new RequestContext("e2e-" + key, key, Map.of());
        return businessService.callWithContext(ctx, name, key, timeout);
    }

    @Test
    @DisplayName("健康下游返回成功且指标累计 success")
    void healthyCallSucceeds() {
        DownstreamService svc = downstreams.get(DownstreamName.PROFILE);
        svc.setBehavior(BehaviorSpec.ok(10L));

        GuardResult r = call(DownstreamName.PROFILE, "p1", 0);
        assertTrue(r.success());
        assertNotNull(r.payload());
        assertEquals("e2e-p1", r.context().get("requestId"));
        assertEquals(1L, svc.invocationCount());

        Map<String, Object> m = guard.metrics("PROFILE");
        assertEquals(1L, m.get("success"));
    }

    @Test
    @DisplayName("下游持续报错先是真实失败，达到阈值后转为 CIRCUIT_OPEN 降级（可区分）")
    void failureThenCircuitOpenFallbackAreDistinguishable() {
        DownstreamService svc = downstreams.get(DownstreamName.INVENTORY);
        svc.setBehavior(BehaviorSpec.error(0L, "inventory down"));

        GuardResult f1 = call(DownstreamName.INVENTORY, "i1", 500L);
        assertTrue(f1.failure(), "熔断打开前是真实失败");
        assertNotNull(f1.errorMessage());
        GuardResult f2 = call(DownstreamName.INVENTORY, "i2", 500L);
        assertTrue(f2.failure());
        GuardResult f3 = call(DownstreamName.INVENTORY, "i3", 500L);
        assertTrue(f3.failure());

        // 阈值 3、失败率 1.0：第 4 次开始熔断快速失败
        GuardResult rejected = call(DownstreamName.INVENTORY, "i4", 500L);
        assertTrue(rejected.fallback());
        assertEquals(com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason.CIRCUIT_OPEN,
                rejected.reason());
        // 熔断后未触达下游：真实调用仍是 3 次
        assertEquals(3L, svc.invocationCount());

        Map<String, Object> m = guard.metrics("INVENTORY");
        assertEquals(3L, m.get("failure"));
        assertTrue((Long) m.get("fallback") >= 1L);
        assertTrue((Long) m.get("rejected") >= 1L);
    }

    @Test
    @DisplayName("慢下游触发超时降级，恢复后调用成功")
    void slowCallTimeoutThenRecover() throws Exception {
        DownstreamService svc = downstreams.get(DownstreamName.POINTS);
        svc.setBehavior(BehaviorSpec.ok(150L));

        GuardResult timeout = call(DownstreamName.POINTS, "pt1", 50L);
        assertTrue(timeout.fallback());
        assertEquals(com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason.TIMEOUT,
                timeout.reason());

        // 等后台在途任务结束后，同键重试复用其成功结果（副作用仍只有一次）
        Thread.sleep(300L);
        GuardResult replay = call(DownstreamName.POINTS, "pt1", 500L);
        assertTrue(replay.success(), "后台已完成，同键重试应复用结果");
        assertTrue(replay.duplicate());
        assertEquals(1L, svc.invocationCount(), "同键重试不得重复执行下游");

        // 新键 + 恢复快速响应：真实成功
        svc.setBehavior(BehaviorSpec.ok(5L));
        GuardResult ok = call(DownstreamName.POINTS, "pt2", 500L);
        assertTrue(ok.success());
        assertFalse(ok.duplicate());
    }
}
