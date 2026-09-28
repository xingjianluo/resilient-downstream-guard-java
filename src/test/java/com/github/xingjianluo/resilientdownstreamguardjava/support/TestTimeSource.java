package com.github.xingjianluo.resilientdownstreamguardjava.support;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用可控时钟：时间只在测试显式推进时增长，保证熔断冷却等时间判定可复现。
 */
public final class TestTimeSource implements TimeSource {

    private final AtomicLong now = new AtomicLong(0L);

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advance(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        now.addAndGet(deltaMillis);
    }

    public void set(long millis) {
        now.set(millis);
    }
}
