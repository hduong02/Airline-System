package com.example.ancillary_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ancillary_service.mapper.MealMapper;
import com.example.ancillary_service.model.Meal;
import com.example.ancillary_service.repository.MealRepository;
import com.example.ancillary_service.service.MealService;
import com.example.payload.request.MealRequest;
import com.example.payload.response.MealResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MealServiceImpl implements MealService {

    private final MealRepository mealRepository;

    @Override
    @Transactional
    public MealResponse createMeal(Long airlineId, MealRequest request) throws Exception {
        if (mealRepository.existsByCodeAndAirlineId(request.getCode(), airlineId))
            throw new Exception("Meal with code " + request.getCode() +
                    " already exists for this airline");

        Meal meal = Meal.builder()
                .code(request.getCode())
                .name(request.getName())
                .mealType(request.getMealType())
                .dietaryRestriction(request.getDietaryRestriction())
                .ingredients(request.getIngredients())
                .imageUrl(request.getImageUrl())
                .requiresAdvanceBooking(request.getRequiresAdvanceBooking() != null
                        ? request.getRequiresAdvanceBooking() : false)
                .advanceBookingHours(request.getAdvanceBookingHours())
                .displayOrder(request.getDisplayOrder() != null ?
                        request.getDisplayOrder() : 0)
                .airlineId(airlineId)
                .build();

        Meal savedMeal = mealRepository.save(meal);
        return MealMapper.toResponse(savedMeal);
    }

    @Override
    public MealResponse getMealById(Long id) throws Exception {
        Meal meal = mealRepository.findById(id)
                .orElseThrow(() -> new Exception("Meal not found with id: " + id));
        return MealMapper.toResponse(meal);
    }

    @Override
    public List<MealResponse> getByAirlineId(Long airlineId) {
        return mealRepository.findByAirlineId(airlineId).stream()
                .map(MealMapper::toResponse)
                .collect(Collectors.toList());
    }


    @Override
    public MealResponse updateMeal(Long airlineId, Long id, MealRequest request)
            throws Exception {
        Meal meal = mealRepository.findById(id)
                .orElseThrow(() -> new Exception("Meal not found with id: " + id));

        if (request.getCode() != null && mealRepository.existsByAirlineIdAndCodeAndIdNot(
                airlineId, request.getCode(), meal.getId())) {
            throw new Exception("Meal with code " + request.getCode() +
                    " already exists for this airline");
        }

        meal.setCode(request.getCode());
        meal.setName(request.getName());
        meal.setMealType(request.getMealType());
        meal.setDietaryRestriction(request.getDietaryRestriction());
        meal.setIngredients(request.getIngredients());
        meal.setImageUrl(request.getImageUrl());
        meal.setRequiresAdvanceBooking(request.getRequiresAdvanceBooking());
        meal.setAdvanceBookingHours(request.getAdvanceBookingHours());
        meal.setDisplayOrder(request.getDisplayOrder());

        Meal updatedMeal = mealRepository.save(meal);
        return MealMapper.toResponse(updatedMeal);
    }

    @Override
    @Transactional
    public void deleteMeal(Long id) throws Exception {
        Meal meal = mealRepository.findById(id)
                .orElseThrow(() -> new Exception("Meal not found with id: " + id));
        mealRepository.delete(meal);
    }

    @Override
    public MealResponse updateAvailability(Long id, Boolean availability) throws Exception {
        Meal meal = mealRepository.findById(id)
                .orElseThrow(() -> new Exception("Meal not found with id: " + id));
        meal.setAvailable(availability);
        Meal updatedMeal = mealRepository.save(meal);
        return MealMapper.toResponse(updatedMeal);
    }
}