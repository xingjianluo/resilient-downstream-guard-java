package com.github.xingjianluo.resilientdownstreamguardjava.guard.trace;

import java.util.Map;

/**
 * 一次业务请求的跨线程上下文：请求 ID、业务键等不可变信息。
 */
public final class RequestContext {
    private final String requestId;
    private final String businessKey;
    private final Map<String, String> attributes;

    public RequestContext(String requestId, String businessKey, Map<String, String> attributes) {
        this.requestId = requestId;
        this.businessKey = businessKey;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String requestId() { return requestId; }
    public String businessKey() { return businessKey; }
    public Map<String, String> attributes() { return attributes; }

    public Map<String, String> asMap() {
        return Map.of("requestId", requestId == null ? "" : requestId,
                "businessKey", businessKey == null ? "" : businessKey);
    }
}
