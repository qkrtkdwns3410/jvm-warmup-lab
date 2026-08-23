package com.sangjun.lab.jvmwarmup;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LabController {
    private final ColdPathGate gate;
    private final WarmupState warmupState;
    public LabController(ColdPathGate gate, WarmupState warmupState) {
        this.gate = gate; this.warmupState = warmupState;
    }
    @GetMapping("/api/lab/status")
    public Map<String, Object> status() {
        return Map.of("coldSimulatorEnabled", gate.isSimulatorEnabled(),
                "coldPathInitialized", gate.isInitialized(),
                "holdDurationMillis", gate.holdDurationMillis(),
                "warmupCompleted", warmupState.isCompleted());
    }
    @PostMapping("/api/lab/cold-path/reset")
    public Map<String, Object> reset() { gate.reset(); return status(); }
}
