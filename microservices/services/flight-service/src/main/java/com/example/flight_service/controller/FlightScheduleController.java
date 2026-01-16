package com.example.flight_service.controller;

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
            @RequestHeader("X-Airline-Id") Long airlineId,
            @Valid @RequestBody FlightScheduleRequest request) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                flightScheduleService.createFlightSchedule(airlineId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightScheduleResponse> getFlightScheduleById(
            @PathVariable Long id) throws Exception {
        return ResponseEntity.ok(flightScheduleService.getFlightScheduleById(id));
    }

    @GetMapping
    public ResponseEntity<?> getFlightSchedules(
            @RequestHeader("X-Airline-Id") Long airlineId
    ) {
        return ResponseEntity.ok(
                flightScheduleService.getFlightScheduleByAirline(airlineId)
        );
    }


    @PutMapping("/{id}")
    public ResponseEntity<FlightScheduleResponse> updateFlightSchedule(
            @PathVariable Long id,
            @Valid @RequestBody FlightScheduleRequest request) throws Exception {
        return ResponseEntity.ok(flightScheduleService.updateFlightSchedule(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteFlightSchedule(@PathVariable Long id) throws Exception {
        flightScheduleService.deleteFlightSchedule(id);
        return ResponseEntity.noContent().build();
    }

}