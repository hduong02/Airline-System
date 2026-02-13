package com.example.ancillary_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.ancillary_service.service.FlightMealService;
import com.example.payload.request.FlightMealRequest;
import com.example.payload.response.FlightMealResponse;

import java.util.List;

@RestController
@RequestMapping("/api/flight-meals")
@RequiredArgsConstructor
public class FlightMealController {

    private final FlightMealService flightMealService;

    @PostMapping
    public ResponseEntity<FlightMealResponse> createFlightMeal(
            @Valid @RequestBody FlightMealRequest request) throws Exception {
        FlightMealResponse response = flightMealService.createFlightMeal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/price/total")
    public ResponseEntity<Double> calculateMealPrice(@RequestBody List<Long> requests) {
        double responses = flightMealService.calculateMealPrice(requests);
        return ResponseEntity.status(HttpStatus.OK).body(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlightMealResponse> getFlightMealById(@PathVariable Long id)
            throws Exception {
        return ResponseEntity.ok(flightMealService.getFlightMealById(id));
    }

    @GetMapping("/flight/{flightId}")
    public ResponseEntity<List<FlightMealResponse>> getMealsByFlightId(
            @PathVariable Long flightId) {
        return ResponseEntity.ok(flightMealService.getByFlightId(flightId));
    }

    @GetMapping("/all")
    public ResponseEntity<List<FlightMealResponse>> getMealsByIds(
            @RequestParam List<Long> Ids) {
        return ResponseEntity.ok(flightMealService.getAllByIds(Ids));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlightMealResponse> updateFlightMeal(
            @PathVariable Long id,
            @Valid @RequestBody FlightMealRequest request) throws Exception {
        return ResponseEntity.ok(flightMealService.updateFlightMeal(id, request));
    }

    @PatchMapping("/{id}/availability")
    public ResponseEntity<FlightMealResponse> updateFlightMealAvailability(
            @PathVariable Long id, @RequestParam Boolean available) throws Exception {
        return ResponseEntity.ok(flightMealService.updateFlightMealAvailability(id, available));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlightMeal(@PathVariable Long id)
            throws Exception {
        flightMealService.deleteFlightMeal(id);
        return ResponseEntity.noContent().build();
    }
}
