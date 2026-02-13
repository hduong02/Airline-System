package com.example.ancillary_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ancillary_service.model.FlightMeal;

import java.util.List;

public interface FlightMealRepository extends JpaRepository<FlightMeal, Long> {
    List<FlightMeal> findByFlightId(Long flightId);

    boolean existsByFlightIdAndMealId(Long flightId, Long mealId);
}
