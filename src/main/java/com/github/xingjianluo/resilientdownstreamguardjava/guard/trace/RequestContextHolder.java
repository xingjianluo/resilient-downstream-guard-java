package com.github.xingjianluo.resilientdownstreamguardjava.guard.trace;

import java.util.Map;

/**
 * 基于 InheritableThreadLocal 的请求上下文持有器，
 * 保证异步/超时引起的线程切换后仍能关联同一次请求。
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new InheritableThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static String currentRequestId() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.requestId();
    }

    public static Map<String, String> currentContextMap() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? Map.of("requestId", "", "businessKey", "") : ctx.asMap();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
