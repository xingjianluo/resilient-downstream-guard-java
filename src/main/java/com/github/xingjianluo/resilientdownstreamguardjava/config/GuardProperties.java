package com.github.xingjianluo.resilientdownstreamguardjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * guard.downstreams.<name> 形式的配置绑定（骨架）。
 */
@ConfigurationProperties(prefix = "guard")
public class GuardProperties {

    private Map<String, DownstreamView> downstreams = new LinkedHashMap<>();

    public Map<String, DownstreamView> getDownstreams() {
        return downstreams;
    }

    public void setDownstreams(Map<String, DownstreamView> downstreams) {
        this.downstreams = downstreams;
    }

    /** 单个下游的配置视图。 */
    public static class DownstreamView {
        private int maxConcurrent = 4;
        private int queueCapacity = 2;
        private long timeoutMillis = 500;
        private int windowSize = 20;
        private int minimumCalls = 5;
        private double failureRateThreshold = 0.5;
        private long openStateCooldownMillis = 2000;
        private int halfOpenMaxProbes = 2;
        private int halfOpenSuccessToClose = 2;
        private long dedupRetentionMillis = 3000;
        private int dedupMaxEntries = 1024;

        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int v) { this.maxConcurrent = v; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
        public long getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(long v) { this.timeoutMillis = v; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int v) { this.windowSize = v; }
        public int getMinimumCalls() { return minimumCalls; }
        public void setMinimumCalls(int v) { this.minimumCalls = v; }
        public double getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(double v) { this.failureRateThreshold = v; }
        public long getOpenStateCooldownMillis() { return openStateCooldownMillis; }
        public void setOpenStateCooldownMillis(long v) { this.openStateCooldownMillis = v; }
        public int getHalfOpenMaxProbes() { return halfOpenMaxProbes; }
        public void setHalfOpenMaxProbes(int v) { this.halfOpenMaxProbes = v; }
        public int getHalfOpenSuccessToClose() { return halfOpenSuccessToClose; }
        public void setHalfOpenSuccessToClose(int v) { this.halfOpenSuccessToClose = v; }
        public long getDedupRetentionMillis() { return dedupRetentionMillis; }
        public void setDedupRetentionMillis(long v) { this.dedupRetentionMillis = v; }
        public int getDedupMaxEntries() { return dedupMaxEntries; }
        public void setDedupMaxEntries(int v) { this.dedupMaxEntries = v; }
    }
}
