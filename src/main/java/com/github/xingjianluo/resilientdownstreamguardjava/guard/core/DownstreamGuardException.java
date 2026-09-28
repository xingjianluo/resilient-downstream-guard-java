package com.github.xingjianluo.resilientdownstreamguardjava.guard.core;

/**
 * 下游调用过程中发生的异常（业务异常、超时中断等），供守卫内部识别使用。
 */
public class DownstreamGuardException extends RuntimeException {
    public DownstreamGuardException(String message) {
        super(message);
    }

    public DownstreamGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
