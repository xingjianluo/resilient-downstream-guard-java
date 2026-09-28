package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 单次调用的上下文，承载跨线程传递的关联标识（如 requestId）。
 */
public final class CallContext {
    private final String requestId;

    public CallContext(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
