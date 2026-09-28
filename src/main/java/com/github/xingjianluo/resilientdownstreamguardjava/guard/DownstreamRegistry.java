package com.github.xingjianluo.resilientdownstreamguardjava.guard;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下游注册表：按名字注册配置并惰性持有运行时；注册与查询并发安全。
 */
public final class DownstreamRegistry {

    private final Clock clock;
    private final ConcurrentHashMap<String, DownstreamRuntime> runtimes = new ConcurrentHashMap<>();

    public DownstreamRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 注册一个下游；重复注册将被忽略并返回已存在实例。 */
    public DownstreamRuntime register(DownstreamConfig config) {
        return runtimes.computeIfAbsent(config.getName(),
                name -> new DownstreamRuntime(config, clock));
    }

    public DownstreamRuntime get(String name) {
        return runtimes.get(name);
    }

    public Collection<DownstreamRuntime> all() {
        return Collections.unmodifiableCollection(runtimes.values());
    }
}
