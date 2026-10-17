package com.project.Automated_Platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AutomatedPlatformApplication {

	public static void main(String[] args) {

		SpringApplication.run(AutomatedPlatformApplication.class, args);
	}

}
