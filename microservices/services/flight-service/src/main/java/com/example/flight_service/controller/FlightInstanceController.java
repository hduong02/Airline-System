package com.example.flight_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.flight_service.service.FlightInstanceService;
import com.example.payload.request.FlightInstanceRequest;
import com.example.payload.response.ApiResponse;
import com.example.payload.response.FlightInstanceResponse;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/flight-instances")
@RequiredArgsConstructor
public class FlightInstanceController {

    private final FlightInstanceService flightInstanceService;

    @PostMapping
    public ResponseEntity<FlightInstanceResponse> createFlightInstance(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody FlightInstanceRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(flightInstanceService
                        .createFlightInstance(userId,request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightInstanceResponse> getFlightInstanceById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(flightInstanceService.getFlightInstanceById(id));
    }

    @GetMapping()
    public ResponseEntity<Page<FlightInstanceResponse>> getByAirlineId(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Long departureAirportId,
            @RequestParam(required = false) Long arrivalAirportId,
            @RequestParam(required = false) Long flightId,
            @RequestParam(required = false) LocalDate onDate,
            Pageable pageable) {
        return ResponseEntity.ok(flightInstanceService.getByAirlineId(
                userId,
                departureAirportId,
                arrivalAirportId,
                flightId,
                onDate,
                pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlightInstanceResponse> updateFlightInstance(
            @PathVariable Long id,
            @RequestBody FlightInstanceRequest request) throws Exception {
        return ResponseEntity.ok(flightInstanceService.updateFlightInstance(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteFlightInstance(@PathVariable Long id) throws Exception {
        flightInstanceService.deleteFlightInstance(id);
        return ResponseEntity.noContent().build();
    }
}
