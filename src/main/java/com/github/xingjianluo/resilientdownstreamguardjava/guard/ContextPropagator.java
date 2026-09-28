package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 跨线程上下文传播：提交方线程绑定上下文，下游执行线程中可取出，
 * 异步或超时切换线程后仍能关联同一次请求。
 */
public final class ContextPropagator {

    private static final ThreadLocal<CallContext> HOLDER = new ThreadLocal<>();

    public void set(CallContext context) {
        HOLDER.set(context);
    }

    public CallContext get() {
        return HOLDER.get();
    }

    public void clear() {
        HOLDER.remove();
    }

    /** 捕获当前线程上下文快照，供另一线程恢复使用。 */
    public CallContext capture() {
        return HOLDER.get();
    }

    /** 执行动作期间在当前线程恢复给定上下文，结束后清理，避免线程复用串号。 */
    public <T> T runWith(CallContext context, java.util.concurrent.Callable<T> action) throws Exception {
        CallContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
