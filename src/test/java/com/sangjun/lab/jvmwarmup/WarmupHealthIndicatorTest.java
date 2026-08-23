package com.sangjun.lab.jvmwarmup;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WarmupHealthIndicatorTest {
    @Test
    void healthStaysDownUntilWarmupCompletes() {
        LabProperties properties = new LabProperties();
        properties.setWarmupEnabled(true);
        WarmupState state = new WarmupState(properties);
        WarmupHealthIndicator indicator = new WarmupHealthIndicator(state);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        state.markCompleted();
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }
}
