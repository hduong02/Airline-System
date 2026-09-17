package com.example.flight_service.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.flight_service.service.FlightScheduleService;
import com.example.payload.request.FlightScheduleRequest;
import com.example.payload.response.ApiResponse;
import com.example.payload.response.FlightScheduleResponse;


@RestController
@RequestMapping("/api/flight-schedules")
@RequiredArgsConstructor
public class FlightScheduleController {

    private final FlightScheduleService flightScheduleService;

    @PostMapping
    public ResponseEntity<FlightScheduleResponse> createFlightSchedule(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody FlightScheduleRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                flightScheduleService.createFlightSchedule(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightScheduleResponse> getFlightScheduleById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(flightScheduleService.getFlightScheduleById(id));
    }

    @GetMapping
    public ResponseEntity<?> getFlightSchedules(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(flightScheduleService.getFlightScheduleByAirline(userId));
    }


    @GetMapping("/page")
    public ResponseEntity<Page<FlightScheduleResponse>> getFlightSchedulePage(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be between 1 and 100");
        }
        return ResponseEntity.ok(flightScheduleService.getFlightSchedulePage(userId, page, size));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlightScheduleResponse> updateFlightSchedule(
            @PathVariable Long id,
            @Valid @RequestBody FlightScheduleRequest request) throws Exception {
        return ResponseEntity.ok(flightScheduleService.updateFlightSchedule(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteFlightSchedule(@PathVariable Long id)
            throws Exception {
        flightScheduleService.deleteFlightSchedule(id);
        return ResponseEntity.noContent().build();
    }

}