package com.example.flight_service.controller;

import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.payload.response.BookingFlightValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Service-to-service route; the public gateway does not expose /internal/**. */
@RestController
@RequiredArgsConstructor
public class InternalBookingFlightController {
    private final FlightInstanceRepository instances;

    @GetMapping("/internal/flight-instances/{id}/booking-validation")
    @Transactional(readOnly = true)
    public BookingFlightValidationResponse validate(@PathVariable Long id) {
        return instances.findBookingValidationById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight instance not found"));
    }
}
