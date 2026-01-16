package com.example.flight_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.enums.FlightStatus;
import com.example.flight_service.service.FlightService;
import com.example.payload.request.FlightRequest;
import com.example.payload.response.FlightResponse;


@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightService flightService;

    @PostMapping
    public ResponseEntity<FlightResponse> createFlight(
            @RequestHeader("Airline-Id") Long airlineId,
            @Valid @RequestBody FlightRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flightService.createFlight(airlineId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightResponse> getFlightById(@PathVariable Long id)  {
        return ResponseEntity.ok(flightService.getFlightById(id));
    }


    @GetMapping("/airline")
    public ResponseEntity<Page<FlightResponse>> getFlightsByAirline(
            @RequestHeader("Airline-Id") Long airlineId,
            @RequestParam(required = false) Long departureAirportId,
            @RequestParam(required = false) Long arrivalAirportId,
            Pageable pageable) {
        return ResponseEntity.ok(flightService.getFlightsByAirline(
                airlineId,
                departureAirportId,
                arrivalAirportId,
                pageable
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlightResponse> updateFlight(
            @PathVariable Long id,
            @RequestBody FlightRequest request) throws Exception {
        return ResponseEntity.ok(flightService.updateFlight(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<FlightResponse> changeStatus(
            @PathVariable Long id,
            @RequestParam FlightStatus status) throws Exception {
        return ResponseEntity.ok(flightService.changeStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlight(@PathVariable Long id,
                @RequestHeader("Airline-Id") Long airlineId) throws Exception{
        flightService.deleteFlight(airlineId, id);
        return ResponseEntity.noContent().build();
    }

}