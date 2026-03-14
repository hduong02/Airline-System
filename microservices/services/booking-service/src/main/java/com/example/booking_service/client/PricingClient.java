package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.payload.response.FareResponse;


@FeignClient(name = "pricing-service")
public interface PricingClient {

    @GetMapping("/api/fares/{id}")
    FareResponse getFareById(@PathVariable Long id);

}