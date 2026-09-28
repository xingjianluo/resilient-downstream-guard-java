package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

/**
 * 模拟下游访问异常，表示真实调用到了下游但下游返回错误（区别于守卫拒绝）。
 */
public class DownstreamAccessException extends RuntimeException {

    private final String downstream;

    public DownstreamAccessException(String downstream, String message) {
        super(message);
        this.downstream = downstream;
    }

    public String downstream() {
        return downstream;
    }
}
