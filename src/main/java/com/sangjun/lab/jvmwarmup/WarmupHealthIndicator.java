package com.sangjun.lab.jvmwarmup;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("warmup")
public class WarmupHealthIndicator implements HealthIndicator {
    private final WarmupState state;
    public WarmupHealthIndicator(WarmupState state) { this.state = state; }
    @Override public Health health() {
        return state.isCompleted()
                ? Health.up().withDetail("warmup", "completed").build()
                : Health.down().withDetail("warmup", "pending").build();
    }
}
