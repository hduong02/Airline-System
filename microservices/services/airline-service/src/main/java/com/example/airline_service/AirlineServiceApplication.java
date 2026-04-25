package com.example.airline_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableJpaAuditing
@EnableCaching
public class AirlineServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AirlineServiceApplication.class, args);
	}

}
