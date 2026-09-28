package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

/**
 * 下游服务抽象。LocalMockDownstreamService 为可注入行为的本地实现。
 */
public interface DownstreamService {

    /** 发起一次带业务键的调用（业务键仅用于日志关联，不做去重）。 */
    String invoke(String businessKey);

    /** 注入后续调用的行为（正常耗时/故障）。 */
    void setBehavior(BehaviorSpec spec);

    BehaviorSpec currentBehavior();

    /** 真实执行次数（用于断言下游副作用次数）。 */
    long invocationCount();

    /** 清空计数（不改变行为）。 */
    void resetInvocationCount();
}
