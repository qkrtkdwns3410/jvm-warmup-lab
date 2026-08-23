package com.sangjun.lab.jvmwarmup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sangjun.lab.jvmwarmup.product.ProductService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "lab.warmup-enabled=false",
        "lab.cold.simulator-enabled=true",
        "lab.cold.hold-duration=800ms",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=250"
})
class ColdPathGateIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("warmup_lab").withUsername("lab").withPassword("lab");

    @Autowired ProductService productService;
    @Autowired ColdPathGate gate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @AfterEach
    void reset() { if (gate.isInitialized()) gate.reset(); }

    @Test
    void coldPathExhaustsSmallPoolButPreWarmedPathDoesNot() throws Exception {
        assertThat(callConcurrently(6)).isNotEmpty();
        productService.find(1L);
        assertThat(callConcurrently(6)).isEmpty();
    }

    private List<Throwable> callConcurrently(int count) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try { productService.find(1L); return null; }
                    catch (Throwable throwable) { return throwable; }
                }));
            }
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(5, TimeUnit.SECONDS);
                if (failure != null) failures.add(failure);
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }
}
