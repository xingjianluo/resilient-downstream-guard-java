package com.github.xingjianluo.resilientdownstreamguardjava.config;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.ContextPropagator;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.SystemClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册保护组件 Bean（骨架）。
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {

    @Bean
    public DownstreamRegistry downstreamRegistry(GuardProperties properties) {
        return null;
    }

    @Bean
    public ContextPropagator contextPropagator() {
        return null;
    }

    @Bean
    public DownstreamGuard downstreamGuard(DownstreamRegistry registry, ContextPropagator propagator) {
        return null;
    }
}
