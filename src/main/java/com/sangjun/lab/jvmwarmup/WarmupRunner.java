package com.sangjun.lab.jvmwarmup;

import com.sangjun.lab.jvmwarmup.product.ProductService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class WarmupRunner implements ApplicationRunner {
    private final LabProperties properties;
    private final ProductService productService;
    private final WarmupState state;
    public WarmupRunner(LabProperties properties, ProductService productService, WarmupState state) {
        this.properties = properties; this.productService = productService; this.state = state;
    }
    @Override public void run(ApplicationArguments args) {
        if (!properties.isWarmupEnabled()) return;
        state.markPending();
        productService.find(1L);
        state.markCompleted();
    }
}
