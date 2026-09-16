package com.example.pricing_service.client;

import com.example.payload.response.AirlineResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "airline-service")
public interface AirlineClient {
    @GetMapping("/api/airlines/admin")
    AirlineResponse getAirlineByOwner(@RequestHeader("X-User-Id") Long userId);
}
