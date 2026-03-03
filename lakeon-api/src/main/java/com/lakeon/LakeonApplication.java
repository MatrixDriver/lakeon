package com.lakeon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LakeonApplication {
    public static void main(String[] args) {
        SpringApplication.run(LakeonApplication.class, args);
    }
}
