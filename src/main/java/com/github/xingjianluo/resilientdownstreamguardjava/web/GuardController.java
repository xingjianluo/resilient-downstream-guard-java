package com.github.xingjianluo.resilientdownstreamguardjava.web;

import com.github.xingjianluo.resilientdownstreamguardjava.downstream.BehaviorSpec;
import com.github.xingjianluo.resilientdownstreamguardjava.downstream.BusinessService;
import com.github.xingjianluo.resilientdownstreamguardjava.downstream.DownstreamService;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.model.GuardResult;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContext;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本地手工验证入口：
 * <pre>
 * GET  /demo/downstreams                 查看下游列表
 * POST /demo/mock/{name}?latency=&error= 注入模拟行为
 * POST /demo/call/{name}?key=&timeout=   发起受保护调用
 * GET  /demo/metrics                     查看全部可观测指标
 * POST /demo/metrics/reset               清零指标
 * </pre>
 */
@RestController
@RequestMapping("/demo")
public class GuardController {

    private final BusinessService businessService;
    private final DownstreamGuard guard;
    private final Map<DownstreamName, DownstreamService> downstreams;

    public GuardController(BusinessService businessService, DownstreamGuard guard,
                           Map<DownstreamName, DownstreamService> downstreams) {
        this.businessService = businessService;
        this.guard = guard;
        this.downstreams = downstreams;
    }

    @GetMapping("/downstreams")
    public Map<String, Object> downstreams() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<DownstreamName, DownstreamService> e : downstreams.entrySet()) {
            DownstreamService s = e.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("behavior", s.currentBehavior());
            d.put("invocationCount", s.invocationCount());
            out.put(e.getKey().name(), d);
        }
        return out;
    }

    @PostMapping("/mock/{name}")
    public Map<String, Object> mock(@PathVariable String name,
                                    @RequestParam(defaultValue = "0") long latency,
                                    @RequestParam(defaultValue = "false") boolean error,
                                    @RequestParam(required = false) String message) {
        DownstreamName dn = DownstreamName.valueOf(name.toUpperCase());
        BehaviorSpec spec = error ? BehaviorSpec.error(latency, message) : BehaviorSpec.ok(latency);
        downstreams.get(dn).setBehavior(spec);
        return Map.of("downstream", dn.name(), "behavior", spec);
    }

    @PostMapping("/call/{name}")
    public Map<String, Object> call(@PathVariable String name,
                                    @RequestParam String key,
                                    @RequestParam(defaultValue = "0") long timeout) {
        DownstreamName dn = DownstreamName.valueOf(name.toUpperCase());
        String requestId = UUID.randomUUID().toString();
        RequestContext ctx = new RequestContext(requestId, key, Map.of());
        GuardResult result = businessService.callWithContext(ctx, dn, key, timeout);
        return toMap(dn, key, requestId, result);
    }

    private Map<String, Object> toMap(DownstreamName dn, String key, String requestId, GuardResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("downstream", dn.name());
        out.put("businessKey", key);
        out.put("outcome", r.success() ? "SUCCESS" : r.fallback() ? "FALLBACK" : "FAILURE");
        out.put("duplicate", r.duplicate());
        out.put("reason", r.reason());
        out.put("payload", r.payload());
        out.put("errorMessage", r.errorMessage());
        out.put("elapsedMillis", r.elapsedMillis());
        out.put("context", r.context());
        return out;
    }

    @GetMapping("/metrics")
    public Map<String, Map<String, Object>> metrics() {
        return guard.metricsAll();
    }

    @PostMapping("/metrics/reset")
    public Map<String, String> reset() {
        for (DownstreamName dn : DownstreamName.values()) {
            guard.resetMetrics(dn.name());
        }
        return Map.of("status", "reset");
    }
}
