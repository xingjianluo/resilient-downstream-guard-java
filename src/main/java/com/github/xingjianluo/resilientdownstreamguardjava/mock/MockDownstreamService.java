package com.github.xingjianluo.resilientdownstreamguardjava.mock;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可注入的本地模拟下游：
 * - 可设定固定延迟、抖动、失败概率/强制全部失败；
 * - 记录被实际执行的次数与每个业务键的执行次数，用于验证"副作用最多一次"；
 * - 可切换"故障模式/正常模式"以驱动熔断打开与恢复。
 */
public final class MockDownstreamService {

    public MockDownstreamService(String name) {
    }

    /** 配置固定延迟（毫秒）。 */
    public void setLatencyMillis(long latencyMillis) {
    }

    /** 配置抖动（毫秒），实际延迟 = 固定延迟 + [0, jitter)。 */
    public void setJitterMillis(long jitterMillis) {
    }

    /** 设置为故障模式：所有调用抛出异常。 */
    public void setFailureMode(boolean failureMode) {
    }

    /** 按业务键执行一次"副作用"，返回结果字符串。 */
    public String invoke(String businessKey) throws Exception {
        return null;
    }

    /** 下游被实际执行的总次数。 */
    public int invocationCount() {
        return 0;
    }

    /** 指定业务键被实际执行的次数（验证去重的关键断言依据）。 */
    public int invocationCountOf(String businessKey) {
        return 0;
    }

    /** 重置统计与模式。 */
    public void reset() {
    }

    public String getName() {
        return null;
    }

    /** 内部统计句柄（骨架，测试辅助用）。 */
    AtomicInteger counter() {
        return null;
    }
}
