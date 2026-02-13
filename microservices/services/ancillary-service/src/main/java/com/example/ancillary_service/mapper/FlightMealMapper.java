package com.example.ancillary_service.mapper;

import com.example.ancillary_service.model.FlightMeal;
import com.example.payload.response.FlightMealResponse;

public class FlightMealMapper {
    public static FlightMealResponse toResponse(FlightMeal flightMeal) {
        if (flightMeal == null) return null;

        return FlightMealResponse.builder()
                .id(flightMeal.getId())
                .flightId(flightMeal.getFlightId())
                .meal(MealMapper.toResponse(flightMeal.getMeal()))
                .available(flightMeal.getAvailable())
                .price(flightMeal.getPrice())
                .displayOrder(flightMeal.getDisplayOrder())
                .build();
    }
}