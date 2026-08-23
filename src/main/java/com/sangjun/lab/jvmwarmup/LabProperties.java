package com.sangjun.lab.jvmwarmup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lab")
public class LabProperties {
    private boolean warmupEnabled;
    private final Cold cold = new Cold();
    public boolean isWarmupEnabled() { return warmupEnabled; }
    public void setWarmupEnabled(boolean warmupEnabled) { this.warmupEnabled = warmupEnabled; }
    public Cold getCold() { return cold; }
    public static class Cold {
        private boolean simulatorEnabled;
        private Duration holdDuration = Duration.ofMillis(1500);
        public boolean isSimulatorEnabled() { return simulatorEnabled; }
        public void setSimulatorEnabled(boolean simulatorEnabled) { this.simulatorEnabled = simulatorEnabled; }
        public Duration getHoldDuration() { return holdDuration; }
        public void setHoldDuration(Duration holdDuration) { this.holdDuration = holdDuration; }
    }
}
