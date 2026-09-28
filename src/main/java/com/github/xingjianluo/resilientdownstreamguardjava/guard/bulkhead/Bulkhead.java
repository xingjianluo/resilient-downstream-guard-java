package com.github.xingjianluo.resilientdownstreamguardjava.guard.bulkhead;

import java.util.concurrent.Semaphore;

/**
 * 单下游并发隔离舱：基于信号量限制在途调用数，tryAcquire 不等待，
 * 名额耗尽立即返回 false（由上层转为 BULKHEAD_REJECTED 降级）。
 * 每个下游独立持有一个 Bulkhead，慢下游占满自己的名额不会影响其它下游。
 */
public final class Bulkhead {

    private final String downstreamName;
    private final Semaphore permits;
    private final int maxConcurrent;

    public Bulkhead(String downstreamName, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("bulkhead size must be positive: " + maxConcurrent);
        }
        this.downstreamName = downstreamName;
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    /** 尝试占用一个并发名额，不阻塞等待。 */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /** 归还并发名额（含超时/异常路径，必须保证调用）。 */
    public void release() {
        permits.release();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int activeCount() {
        return maxConcurrent - permits.availablePermits();
    }

    public int availableCount() {
        return permits.availablePermits();
    }

    public String downstreamName() {
        return downstreamName;
    }
}
