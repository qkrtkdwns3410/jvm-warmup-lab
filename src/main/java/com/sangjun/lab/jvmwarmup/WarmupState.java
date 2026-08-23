package com.sangjun.lab.jvmwarmup;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class WarmupState {
    private final AtomicBoolean completed;
    public WarmupState(LabProperties properties) {
        this.completed = new AtomicBoolean(!properties.isWarmupEnabled());
    }
    public void markCompleted() { completed.set(true); }
    public void markPending() { completed.set(false); }
    public boolean isCompleted() { return completed.get(); }
}
