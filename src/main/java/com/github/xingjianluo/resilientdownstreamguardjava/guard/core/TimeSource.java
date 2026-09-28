package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 时间来源抽象，便于测试时以可控时钟替换系统时钟。
 */
public interface TimeSource {
    long nowMillis();
}
