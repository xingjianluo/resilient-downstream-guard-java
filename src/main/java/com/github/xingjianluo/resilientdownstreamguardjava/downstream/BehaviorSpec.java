package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

/**
 * 本地模拟下游的行为规格：每次调用的耗时与是否抛出异常，可在运行时注入切换，
 * 用于验证慢调用、持续故障与恢复。
 */
public record BehaviorSpec(long latencyMillis, boolean error, String message) {

    public static BehaviorSpec ok(long latencyMillis) {
        return new BehaviorSpec(latencyMillis, false, null);
    }

    public static BehaviorSpec error(long latencyMillis, String message) {
        return new BehaviorSpec(latencyMillis, true, message);
    }
}
