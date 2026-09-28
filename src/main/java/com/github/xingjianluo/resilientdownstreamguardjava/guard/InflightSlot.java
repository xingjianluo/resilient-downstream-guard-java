package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import java.util.concurrent.CountDownLatch;

/**
 * 同业务键在途调用槽：第一个提交者（leader）真正执行一次下游，
 * 其它并发提交者（follower）在同一槽上等待并共享结果。
 */
public final class InflightSlot {

    private final CountDownLatch done = new CountDownLatch(1);
    private volatile GuardResult<?> result;

    /** leader 完成后登记结果并唤醒全部等待者。 */
    public void complete(GuardResult<?> result) {
        this.result = result;
        done.countDown();
    }

    /** follower 无限等待 leader 结果；被中断时返回 null（由调用方归类为 INTERRUPTED）。 */
    public GuardResult<?> await() {
        try {
            done.await();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 带超时的等待；超时或中断返回 null。 */
    public GuardResult<?> await(long timeoutMillis) {
        try {
            if (done.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return result;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
