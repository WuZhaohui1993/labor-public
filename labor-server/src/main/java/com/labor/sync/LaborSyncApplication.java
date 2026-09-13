package com.labor.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication
public class LaborSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(LaborSyncApplication.class, args);
    }
}

