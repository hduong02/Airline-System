package com.example.flight_service.controller;

import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.payload.request.FlightDepartureFilterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;

// Service-to-service route; the public gateway does not expose /internal/**.
@RestController
@RequiredArgsConstructor
public class InternalFlightDepartureController {
    private final FlightInstanceRepository instances;

    @PostMapping("/internal/flight-instances/departure-filter")
    @Transactional(readOnly = true)
    public List<Long> filter(@Valid @RequestBody FlightDepartureFilterRequest request) {
        if (request.ids().isEmpty()) return List.of();
        return instances.findIdsByDepartureWindow(request.ids(), request.boundary(), request.upcoming());
    }
}
