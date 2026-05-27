package com.school.emotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmotionApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmotionApplication.class, args);
    }
}
