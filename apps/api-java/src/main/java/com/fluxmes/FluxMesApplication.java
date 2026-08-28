package com.fluxmes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FluxMesApplication {

    public static void main(String[] args) {
        SpringApplication.run(FluxMesApplication.class, args);
    }
}
