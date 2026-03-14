package com.example.flight_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;

@FeignClient(name = "airline-service")
public interface AirlineClient {

    @GetMapping("/api/airlines/{airlineId}")
    AirlineResponse getAirlineById(@PathVariable Long airlineId);

    @GetMapping("/api/aircrafts/{id}")
    AircraftResponse getAircraftById(@PathVariable("id") Long id);
}