package com.example.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.example.payload.request.FlightDepartureFilterRequest;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightResponse;
import java.util.List;

@FeignClient(name = "flight-service")
public interface FlightClient {
    @PostMapping("/internal/flight-instances/departure-filter")
    List<Long> filterDepartures(@RequestBody FlightDepartureFilterRequest request);

    @GetMapping("/api/flights/{id}")
    FlightResponse getFlightById(@PathVariable Long id);

    @GetMapping("/api/flight-instances/{id}")
    FlightInstanceResponse getFlightInstanceById(@PathVariable Long id);

}
