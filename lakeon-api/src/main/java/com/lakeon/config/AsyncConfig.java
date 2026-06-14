package com.lakeon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "databaseCreateExecutor")
    public Executor databaseCreateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 2026-04-23: bumped from 2/4/10 after Phase 2 auto-provisioning
        // stress exposed provisioning queue contention when E2E spins up
        // multiple disposable tenants in parallel (each tenant triggers
        // at least 2 DB provisionings: its LakebaseFS store + its memory
        // base). 4/8/20 gives enough headroom without pressuring the
        // Neon pageserver too hard.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("db-create-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
