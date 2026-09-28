package com.github.xingjianluo.resilientdownstreamguardjava.guard.metrics;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.CircuitStatus;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.DownstreamMetrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按下游维度汇总可观测指标：成功/失败/超时/降级/拒绝/复用计数与熔断状态。
 * 计数落在 DownstreamMetrics 的同步方法上，并发下不丢失；
 * 每个下游固定一份指标对象，长期运行不产生新对象。
 */
public final class GuardMetricsRegistry {

    private final ConcurrentHashMap<String, DownstreamMetrics> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Supplier<CircuitStatus>> circuits = new ConcurrentHashMap<>();

    public GuardMetricsRegistry() {
    }

    public DownstreamMetrics forDownstream(String downstream) {
        return metrics.computeIfAbsent(downstream, DownstreamMetrics::new);
    }

    public void recordSuccess(String downstream) {
        forDownstream(downstream).recordSuccess();
    }

    public void recordFailure(String downstream) {
        forDownstream(downstream).recordFailure();
    }

    public void recordTimeout(String downstream) {
        forDownstream(downstream).recordTimeout();
    }

    public void recordFallback(String downstream, FallbackReason reason) {
        DownstreamMetrics m = forDownstream(downstream);
        m.recordFallback();
        if (isRejected(reason)) {
            m.recordRejected();
        }
    }

    public void recordDuplicate(String downstream) {
        forDownstream(downstream).recordDuplicate();
    }

    public void registerCircuit(String downstream, Supplier<CircuitStatus> statusSupplier) {
        circuits.putIfAbsent(downstream, statusSupplier);
    }

    public static boolean isRejected(FallbackReason reason) {
        return reason == FallbackReason.CIRCUIT_OPEN
                || reason == FallbackReason.HALF_OPEN_PROBE_REJECTED
                || reason == FallbackReason.BULKHEAD_REJECTED;
    }

    public Map<String, Object> snapshot(String downstream) {
        DownstreamMetrics m = forDownstream(downstream);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", m.totalCount());
        out.put("success", m.successCount());
        out.put("failure", m.failureCount());
        out.put("timeout", m.timeoutCount());
        out.put("fallback", m.fallbackCount());
        out.put("rejected", m.rejectedCount());
        out.put("duplicate", m.duplicateCount());
        Supplier<CircuitStatus> supplier = circuits.get(downstream);
        if (supplier != null) {
            out.put("circuit", circuitToMap(supplier.get()));
        }
        return out;
    }

    public Map<String, Map<String, Object>> snapshotAll() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        metrics.keySet().forEach(d -> all.put(d, snapshot(d)));
        return all;
    }

    private static Map<String, Object> circuitToMap(CircuitStatus s) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("state", s.state().name());
        c.put("openedAt", s.openedAtMillis());
        c.put("nextProbeAt", s.nextProbeAtMillis());
        c.put("activeProbes", s.activeProbes());
        c.put("windowFailures", s.windowFailures());
        c.put("windowTotal", s.windowTotal());
        c.put("failureRatio", s.failureRatio());
        return c;
    }

    public void reset(String downstream) {
        forDownstream(downstream).reset();
    }
}
