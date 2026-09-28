package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 调用未成功返回时的原因分类，保证"被拒绝"和"真正失败"可区分。
 */
public enum FailureReason {
    /** 熔断器打开，快速拒绝。 */
    CIRCUIT_OPEN,
    /** 隔离仓资源（线程/排队名额）耗尽。 */
    BULKHEAD_REJECTED,
    /** 调用超过配置的超时时间。 */
    TIMEOUT,
    /** 下游业务本身抛出异常。 */
    DOWNSTREAM_ERROR,
    /** 等待共享中的同键调用结果时被中断。 */
    INTERRUPTED
}
