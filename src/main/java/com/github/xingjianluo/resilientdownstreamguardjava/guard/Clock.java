package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 时钟抽象，生产用系统时钟，测试用可控时钟，保证时间相关判定可复现。
 */
public interface Clock {
    long nowMillis();
}
