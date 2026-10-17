package com.xiuxian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XiuxianApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiuxianApplication.class, args);
    }
}
