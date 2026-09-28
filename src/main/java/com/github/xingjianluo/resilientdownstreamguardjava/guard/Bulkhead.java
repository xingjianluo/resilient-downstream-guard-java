package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单下游隔离仓：固定大小线程池 + 有界队列 + AbortPolicy。
 *
 * 每个下游一个实例，慢下游只会耗尽自己的线程与队列而被拒绝（BULKHEAD_REJECTED），
 * 不占用也不拖垮其它下游。线程为守护线程并按下游命名，便于日志与线程栈定位。
 */
public final class Bulkhead {

    private static final Logger log = LoggerFactory.getLogger(Bulkhead.class);

    private final String name;
    private final DownstreamConfig config;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    public Bulkhead(String name, DownstreamConfig config) {
        this.name = name;
        this.config = config;
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "bulkhead-" + name + "-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                config.getMaxConcurrent(),
                config.getMaxConcurrent(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.getQueueCapacity())),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 尝试提交任务。
     *
     * @return 任务句柄；池与队列都满时返回 null，调用方按 BULKHEAD_REJECTED 降级
     */
    public Future<?> submit(Runnable task) {
        try {
            Future<?> future = executor.submit(task);
            log.debug("[bulkhead:{}] accepted, active={}, queued={}",
                    name, executor.getActiveCount(), executor.getQueue().size());
            return future;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            long n = rejectedCount.incrementAndGet();
            log.warn("[bulkhead:{}] rejected, active={}, queued={}, totalRejected={}",
                    name, executor.getActiveCount(), executor.getQueue().size(), n);
            return null;
        }
    }

    public int poolSize() {
        return config.getMaxConcurrent();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int queuedCount() {
        return executor.getQueue().size();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 关闭线程池（应用停止时调用，避免线程泄漏）。 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
