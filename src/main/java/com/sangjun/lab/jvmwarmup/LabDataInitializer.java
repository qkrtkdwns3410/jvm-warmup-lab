package com.sangjun.lab.jvmwarmup;

import com.sangjun.lab.jvmwarmup.product.Product;
import com.sangjun.lab.jvmwarmup.product.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class LabDataInitializer implements ApplicationRunner {
    private final ProductRepository repository;
    public LabDataInitializer(ProductRepository repository) { this.repository = repository; }
    @Override public void run(ApplicationArguments args) {
        if (repository.count() == 0) repository.save(new Product("warmup-lab-product"));
    }
}
