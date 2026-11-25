package com.example.fast2smsmockgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Fast2smsMockGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(Fast2smsMockGatewayApplication.class, args);
    }

}
