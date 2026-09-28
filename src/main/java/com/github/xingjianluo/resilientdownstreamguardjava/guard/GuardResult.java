package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 受保护调用的统一结果。
 *
 * @param <T> 业务返回类型
 */
public final class GuardResult<T> {

    private final boolean success;
    private final boolean fallback;
    private final T value;
    private final FailureReason failureReason;
    private final String errorMessage;
    private final String requestId;
    private final String downstream;
    private final String businessKey;
    private final long latencyMillis;
    private final boolean dedupShared;

    public GuardResult(boolean success, boolean fallback, T value, FailureReason failureReason,
                       String errorMessage, String requestId, String downstream, String businessKey,
                       long latencyMillis, boolean dedupShared) {
        this.success = success;
        this.fallback = fallback;
        this.value = value;
        this.failureReason = failureReason;
        this.errorMessage = errorMessage;
        this.requestId = requestId;
        this.downstream = downstream;
        this.businessKey = businessKey;
        this.latencyMillis = latencyMillis;
        this.dedupShared = dedupShared;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFallback() {
        return fallback;
    }

    public T getValue() {
        return value;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDownstream() {
        return downstream;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public long getLatencyMillis() {
        return latencyMillis;
    }

    /** 是否复用了同业务键在途调用的结果（本调用未实际执行下游）。 */
    public boolean isDedupShared() {
        return dedupShared;
    }
}
