package com.example.ancillary_service.service;

import com.example.payload.request.MealRequest;
import com.example.payload.response.MealResponse;

import java.util.List;

public interface MealService {

    MealResponse createMeal(Long userId, MealRequest request) throws Exception;

    MealResponse getMealById(Long id) throws Exception;

    List<MealResponse> getByAirlineId(Long airlineId);

    MealResponse updateMeal(Long airlineId, Long id, MealRequest request) throws Exception;

    void deleteMeal(Long id) throws Exception;

    MealResponse updateAvailability(Long id, Boolean available) throws Exception;


}