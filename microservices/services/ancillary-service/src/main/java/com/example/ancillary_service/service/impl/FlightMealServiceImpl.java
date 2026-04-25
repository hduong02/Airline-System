package com.example.ancillary_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ancillary_service.repository.FlightMealRepository;
import com.example.ancillary_service.repository.MealRepository;
import com.example.ancillary_service.service.FlightMealService;
import com.example.ancillary_service.mapper.FlightMealMapper;
import com.example.ancillary_service.model.FlightMeal;
import com.example.ancillary_service.model.Meal;
import com.example.payload.request.FlightMealRequest;
import com.example.payload.response.FlightMealResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightMealServiceImpl implements FlightMealService {

    private final FlightMealRepository flightMealRepository;
    private final MealRepository mealRepository;

    @Override
    @Transactional
    public FlightMealResponse createFlightMeal(FlightMealRequest request) throws Exception {
        Meal meal = mealRepository.findById(request.getMealId())
                .orElseThrow(() -> new Exception(
                        "Meal not found with id: " + request.getMealId()));

        if (flightMealRepository.existsByFlightIdAndMealId(request.getFlightId(),
                meal.getId())) {
            throw new Exception("Meal is already assigned to this flight");
        }

        FlightMeal flightMeal = FlightMeal.builder()
                .flightId(request.getFlightId())
                .meal(meal)
                .available(request.getAvailable())
                .price(request.getPrice())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .build();

        FlightMeal saved = flightMealRepository.save(flightMeal);
        return FlightMealMapper.toResponse(saved);
    }


    @Override
    @Transactional(readOnly = true)
    public FlightMealResponse getFlightMealById(Long id) throws Exception {
        FlightMeal flightMeal = flightMealRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightMeal not found with id: " + id));
        return FlightMealMapper.toResponse(flightMeal);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlightMealResponse> getByFlightId(Long flightId) {
        return flightMealRepository.findByFlightId(flightId).stream()
                .map(FlightMealMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<FlightMealResponse> getAllByIds(List<Long> Ids) {
        List<FlightMeal> meals = flightMealRepository.findAllById(Ids);
        return meals.stream().map(FlightMealMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public FlightMealResponse updateFlightMeal(Long id, FlightMealRequest request)
            throws Exception {
        FlightMeal flightMeal = flightMealRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightMeal not found with id: " + id));
        flightMeal.setFlightId(request.getFlightId());
        
        if (request.getMealId() != null) {
            Meal meal = mealRepository.findById(request.getMealId())
                    .orElseThrow(() -> new Exception(
                            "Meal not found with id: " + request.getMealId()));
            flightMeal.setMeal(meal);
        }

        flightMeal.setAvailable(request.getAvailable());
        flightMeal.setPrice(request.getPrice());
        flightMeal.setDisplayOrder(request.getDisplayOrder());

        FlightMeal updated = flightMealRepository.save(flightMeal);
        return FlightMealMapper.toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteFlightMeal(Long id) throws Exception {
        FlightMeal flightMeal = flightMealRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightMeal not found with id: " + id));
        flightMealRepository.delete(flightMeal);
    }

    @Override
    @Transactional
    public FlightMealResponse updateFlightMealAvailability(Long id, Boolean available) throws Exception {
        FlightMeal flightMeal = flightMealRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightMeal not found with id: " + id));
        flightMeal.setAvailable(available);
        FlightMeal updated = flightMealRepository.save(flightMeal);
        return FlightMealMapper.toResponse(updated);
    }

    @Override
    public Double calculateMealPrice(List<Long> mealIds) {
        List<FlightMeal> meals=flightMealRepository.findAllById(mealIds);
        double total = 0.0;
        for (FlightMeal flightMeal : meals) {
            total += flightMeal.getPrice();
        }
        return total;
    }
}