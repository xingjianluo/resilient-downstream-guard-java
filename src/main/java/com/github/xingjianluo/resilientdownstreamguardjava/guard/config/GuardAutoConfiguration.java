package com.github.xingjianluo.resilientdownstreamguardjava.guard.config;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.DownstreamGuard;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.SystemTimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.TimeSource;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.metrics.GuardMetricsRegistry;
import com.github.xingjianluo.resilientdownstreamguardjava.guard.trace.TracingExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 守卫相关 Bean 装配：可控时钟（生产为系统时钟）、带上下文传播的调度器、
 * 指标注册表与守卫门面。
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {

    @Bean
    public TimeSource timeSource() {
        return new SystemTimeSource();
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService guardExecutor(TimeSource timeSource) {
        return new TracingExecutor(Executors.newScheduledThreadPool(8));
    }

    @Bean
    public GuardMetricsRegistry guardMetricsRegistry() {
        return new GuardMetricsRegistry();
    }

    @Bean
    public DownstreamGuard downstreamGuard(GuardProperties properties,
                                           GuardMetricsRegistry metricsRegistry,
                                           ScheduledExecutorService guardExecutor,
                                           TimeSource timeSource) {
        return new DownstreamGuard(properties, metricsRegistry, guardExecutor, timeSource);
    }
}
