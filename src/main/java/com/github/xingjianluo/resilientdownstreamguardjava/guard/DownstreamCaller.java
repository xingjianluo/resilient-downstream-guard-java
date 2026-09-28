package com.github.xingjianluo.resilientdownstreamguardjava.guard;

/**
 * 下游调用动作（允许抛异常）。
 */
@FunctionalInterface
public interface DownstreamCaller<T> {
    T call() throws Exception;
}
