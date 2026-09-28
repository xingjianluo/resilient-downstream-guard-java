package com.github.xingjianluo.resilientdownstreamguardjava.tests.support;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.Clock;

import java.util.concurrent.atomic.AtomicLong;

/** 可控时钟：手动推进时间，保证时间相关判定可复现。 */
public final class TestClock implements Clock {

    private final AtomicLong now = new AtomicLong(0L);

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advanceMillis(long delta) {
        now.addAndGet(delta);
    }

    public void setMillis(long value) {
        now.set(value);
    }
}
