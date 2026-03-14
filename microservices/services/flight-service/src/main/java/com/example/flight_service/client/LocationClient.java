package com.example.flight_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.payload.response.AirportResponse;

@FeignClient(name = "location-service")
public interface LocationClient {

    @GetMapping("/api/airports/{id}")
    AirportResponse getAirportById(@PathVariable Long id);
}
