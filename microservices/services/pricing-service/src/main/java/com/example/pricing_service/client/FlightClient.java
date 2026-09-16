package com.example.pricing_service.client;

import com.example.payload.response.FlightResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "flight-service")
public interface FlightClient {
    @GetMapping("/api/flights/{id}")
    FlightResponse getFlightById(@PathVariable("id") Long id);
}
