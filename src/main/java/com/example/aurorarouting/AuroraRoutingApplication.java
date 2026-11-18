package com.example.aurorarouting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AuroraRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuroraRoutingApplication.class, args);
    }
}
