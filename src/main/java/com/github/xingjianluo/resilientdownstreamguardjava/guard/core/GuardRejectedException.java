package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 表示调用在真正触达下游之前就被守卫拒绝（熔断打开 / 半开探测名额满 / 隔离舱耗尽）。
 * 通过幂等 future 传播给同一业务键的等待者，使其结果与“下游真实失败”区分开。
 */
public class GuardRejectedException extends DownstreamGuardException {

    private final FallbackReason reason;

    public GuardRejectedException(FallbackReason reason) {
        super("rejected by guard: " + reason);
        this.reason = reason;
    }

    public FallbackReason reason() {
        return reason;
    }
}
