package com.sangjun.lab.jvmwarmup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LabProperties.class)
public class JvmWarmupLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(JvmWarmupLabApplication.class, args);
    }
}
