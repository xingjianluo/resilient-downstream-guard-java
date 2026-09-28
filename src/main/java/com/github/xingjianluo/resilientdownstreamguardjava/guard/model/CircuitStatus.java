package com.github.xingjianluo.resilientdownstreamguardjava.guard.model;

import com.github.xingjianluo.resilientdownstreamguardjava.guard.core.CircuitState;

/**
 * 熔断器当前状态快照。
 */
public record CircuitStatus(CircuitState state, long openedAtMillis, long nextProbeAtMillis,
                            int activeProbes, int windowFailures, int windowTotal,
                            double failureRatio) {
}
