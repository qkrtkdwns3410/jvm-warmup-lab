package com.sangjun.lab.jvmwarmup;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * A deterministic equivalent of first-use class initialization. Requests enter
 * only after querying MySQL, so they retain a borrowed connection while waiting.
 */
@Component
public class ColdPathGate {
    private final LabProperties properties;
    private final ReentrantLock initializationLock = new ReentrantLock();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    public ColdPathGate(LabProperties properties) { this.properties = properties; }

    public void waitForFirstUseWhileConnectionIsBorrowed() {
        if (!properties.getCold().isSimulatorEnabled() || initialized.get()) return;
        initializationLock.lock();
        try {
            if (!initialized.get()) {
                try {
                    Thread.sleep(properties.getCold().getHoldDuration().toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cold-path initialization interrupted", exception);
                }
                initialized.set(true);
            }
        } finally {
            initializationLock.unlock();
        }
    }
    public void reset() {
        if (initializationLock.isLocked()) throw new IllegalStateException("cold path is initializing");
        initialized.set(false);
    }
    public boolean isInitialized() { return initialized.get(); }
    public boolean isSimulatorEnabled() { return properties.getCold().isSimulatorEnabled(); }
    public long holdDurationMillis() { return properties.getCold().getHoldDuration().toMillis(); }
}
