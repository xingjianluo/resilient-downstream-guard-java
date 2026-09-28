package com.github.xingjianluo.resilientdownstreamguardjava.downstream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.DownstreamName;

/**
 * 装配三个相互独立的本地模拟下游 Bean。
 */
@Configuration
public class DownstreamConfig {

    @Bean
    public Map<DownstreamName, DownstreamService> mockDownstreams() {
        Map<DownstreamName, DownstreamService> map = new EnumMap<>(DownstreamName.class);
        for (DownstreamName name : DownstreamName.values()) {
            map.put(name, new LocalMockDownstreamService(name.name(), BehaviorSpec.ok(10L)));
        }
        return map;
    }
}
