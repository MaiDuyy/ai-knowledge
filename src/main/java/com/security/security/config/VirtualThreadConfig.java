package com.security.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    @Bean(name = "mrpVirtualThreadExecutor")
    public ExecutorService mrpVirtualThreadExecutor() {
        // Return executor that creates a new virtual thread for each task
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
