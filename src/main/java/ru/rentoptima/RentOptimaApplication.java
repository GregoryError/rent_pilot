package ru.rentoptima;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RentOptimaApplication {
    public static void main(String[] args) {
        SpringApplication.run(RentOptimaApplication.class, args);
    }
}
