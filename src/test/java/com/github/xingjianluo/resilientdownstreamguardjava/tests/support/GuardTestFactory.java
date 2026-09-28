package com.github.xingjianluo.resilientdownstreamguardjava.tests.support;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.Clock;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamConfig;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.ContextPropagator;

/** 测试内构建 guard 的工厂（骨架）。 */
public final class GuardTestFactory {

    private GuardTestFactory() {
    }

    public static DownstreamGuard guardWith(Clock clock, DownstreamConfig... configs) {
        return null;
    }

    public static DownstreamConfig config(String name) {
        return null;
    }

    public static ContextPropagator propagator() {
        return null;
    }
}
