package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.RequestContextHolder;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可注入行为的本地模拟下游：按 {@link BehaviorSpec} 睡眠并返回或抛异常。
 * invocationCount 代表真实副作用次数，供断言“下游最多执行一次”。
 */
public class LocalMockDownstreamService implements DownstreamService {

    private final String name;
    private volatile BehaviorSpec behavior;
    private final AtomicLong invocationCount = new AtomicLong();

    public LocalMockDownstreamService(String name, BehaviorSpec initial) {
        this.name = name;
        this.behavior = initial;
    }

    @Override
    public String invoke(String businessKey) {
        long seq = invocationCount.incrementAndGet();
        BehaviorSpec spec = behavior;
        String requestId = RequestContextHolder.currentRequestId();
        System.out.printf("[mock:%s] invoke seq=%d key=%s requestId=%s latency=%dms error=%s%n",
                name, seq, businessKey, requestId, spec.latencyMillis(), spec.error());
        sleep(spec.latencyMillis());
        if (spec.error()) {
            throw new DownstreamAccessException(name,
                    spec.message() == null ? "mock downstream failure" : spec.message());
        }
        return name + ":ok:" + businessKey + ":" + seq;
    }

    @Override
    public void setBehavior(BehaviorSpec spec) {
        System.out.printf("[mock:%s] behavior switched -> latency=%dms error=%s%n",
                name, spec.latencyMillis(), spec.error());
        this.behavior = spec;
    }

    @Override
    public BehaviorSpec currentBehavior() {
        return behavior;
    }

    @Override
    public long invocationCount() {
        return invocationCount.get();
    }

    @Override
    public void resetInvocationCount() {
        invocationCount.set(0L);
    }

    public String name() {
        return name;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamAccessException("interrupted", "sleep interrupted");
        }
    }
}
