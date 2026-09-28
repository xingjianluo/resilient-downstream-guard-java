package com.github.xingjianluo.resilientdownstreamguardjava.guard.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 包装 ScheduledExecutorService：提交任务时快照当前请求上下文并在执行线程恢复，
 * 任务结束后清理，使超时取消、异步回调等线程切换场景下的日志与结果仍能关联同一请求。
 */
public final class TracingExecutor implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    public TracingExecutor(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    private static Runnable wrap(Runnable command) {
        RequestContext snapshot = RequestContextHolder.get();
        return () -> {
            RequestContext previous = RequestContextHolder.get();
            if (snapshot != null) {
                RequestContextHolder.set(snapshot);
            }
            try {
                command.run();
            } finally {
                if (snapshot != null) {
                    if (previous != null) {
                        RequestContextHolder.set(previous);
                    } else {
                        RequestContextHolder.clear();
                    }
                }
            }
        };
    }

    private static <V> Callable<V> wrap(Callable<V> task) {
        RequestContext snapshot = RequestContextHolder.get();
        return () -> {
            RequestContext previous = RequestContextHolder.get();
            if (snapshot != null) {
                RequestContextHolder.set(snapshot);
            }
            try {
                return task.call();
            } finally {
                if (snapshot != null) {
                    if (previous != null) {
                        RequestContextHolder.set(previous);
                    } else {
                        RequestContextHolder.clear();
                    }
                }
            }
        };
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(wrap(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(wrap(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(wrap(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return delegate.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        tasks.forEach(t -> wrapped.add(wrap(t)));
        return delegate.invokeAll(wrapped);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        tasks.forEach(t -> wrapped.add(wrap(t)));
        return delegate.invokeAll(wrapped, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws ExecutionException, InterruptedException {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        tasks.forEach(t -> wrapped.add(wrap(t)));
        return delegate.invokeAny(wrapped);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        tasks.forEach(t -> wrapped.add(wrap(t)));
        return delegate.invokeAny(wrapped, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }

    /** 便于门面以 CompletableFuture 方式提交并保留上下文。 */
    public <V> CompletableFuture<V> supplyAsync(java.util.function.Supplier<V> supplier) {
        return CompletableFuture.supplyAsync(supplier, this);
    }
}
