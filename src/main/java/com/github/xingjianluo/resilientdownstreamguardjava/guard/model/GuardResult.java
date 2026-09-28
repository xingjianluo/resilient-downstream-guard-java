package com.github.xingjianluo.resilientdownstreamguardjava.guard.model;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.FallbackReason;

import java.util.Map;

/**
 * 守卫调用结果。通过 {@link #success}、{@link #fallback}、{@link #failure} 可区分
 * 成功、降级、真实失败三种结局；降级时可通过 {@link #reason()} 进一步区分拒绝与失败。
 */
public final class GuardResult {
    private final boolean success;
    private final boolean fallback;
    private final String payload;
    private final FallbackReason reason;
    private final String errorMessage;
    private final boolean duplicate;
    private final long elapsedMillis;
    private final Map<String, String> context;

    public GuardResult(boolean success, boolean fallback, String payload, FallbackReason reason,
                       String errorMessage, boolean duplicate, long elapsedMillis,
                       Map<String, String> context) {
        this.success = success;
        this.fallback = fallback;
        this.payload = payload;
        this.reason = reason;
        this.errorMessage = errorMessage;
        this.duplicate = duplicate;
        this.elapsedMillis = elapsedMillis;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static GuardResult success(String payload, boolean duplicate, long elapsed, Map<String, String> ctx) {
        return new GuardResult(true, false, payload, null, null, duplicate, elapsed, ctx);
    }

    public static GuardResult fallback(FallbackReason reason, boolean duplicate, long elapsed, Map<String, String> ctx) {
        return new GuardResult(false, true, null, reason, reason.name(), duplicate, elapsed, ctx);
    }

    public static GuardResult failure(String errorMessage, long elapsed, Map<String, String> ctx) {
        return new GuardResult(false, false, null, null, errorMessage, false, elapsed, ctx);
    }

    public boolean success() { return success; }
    public boolean fallback() { return fallback; }
    public boolean failure() { return !success && !fallback; }
    public String payload() { return payload; }
    public FallbackReason reason() { return reason; }
    public String errorMessage() { return errorMessage; }
    public boolean duplicate() { return duplicate; }
    public long elapsedMillis() { return elapsedMillis; }
    public Map<String, String> context() { return context; }
}
