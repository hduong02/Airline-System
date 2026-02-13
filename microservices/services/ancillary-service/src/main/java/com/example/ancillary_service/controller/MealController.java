package com.example.ancillary_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.ancillary_service.service.MealService;
import com.example.payload.request.MealRequest;
import com.example.payload.response.MealResponse;

import java.util.List;

@RestController
@RequestMapping("/api/meals")
@RequiredArgsConstructor
public class MealController {

    private final MealService mealService;

    @PostMapping
    public ResponseEntity<MealResponse> createMeal(
            @RequestHeader("X-Airline-Id") Long airlineId,
            @Valid @RequestBody MealRequest request) throws Exception {
        MealResponse response = mealService.createMeal(airlineId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MealResponse> getMealById(@PathVariable Long id)
            throws Exception {
        return ResponseEntity.ok(mealService.getMealById(id));
    }

    @GetMapping("/airline")
    public ResponseEntity<List<MealResponse>> getMealsByAirlineId(
            @RequestHeader("X-Airline-Id") Long airlineId) {
        return ResponseEntity.ok(mealService.getByAirlineId(airlineId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MealResponse> updateMeal(
            @PathVariable Long id,
            @Valid @RequestBody MealRequest request,
            @RequestHeader("X-Airline-Id") Long airlineId) throws Exception {
        return ResponseEntity.ok(mealService.updateMeal(airlineId, id, request));
    }

    @PatchMapping("/{id}/availability")
    public ResponseEntity<MealResponse> updateMealAvailability(
            @PathVariable Long id,
            @RequestParam Boolean availability) throws Exception {
        return ResponseEntity.ok(mealService.updateAvailability(id, availability));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMeal(@PathVariable Long id) throws Exception {
        mealService.deleteMeal(id);
        return ResponseEntity.noContent().build();
    }
}
