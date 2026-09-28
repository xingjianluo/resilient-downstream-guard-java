package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 生产环境使用的系统时钟。
 */
public final class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
