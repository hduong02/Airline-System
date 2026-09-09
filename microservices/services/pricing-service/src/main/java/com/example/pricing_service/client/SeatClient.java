package com.example.pricing_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.payload.response.CabinClassResponse;

@FeignClient(name = "seat-service")
public interface SeatClient {
    @GetMapping("/api/cabin-classes/{id}")
    CabinClassResponse getCabinClassById(@PathVariable("id") Long id);
}
