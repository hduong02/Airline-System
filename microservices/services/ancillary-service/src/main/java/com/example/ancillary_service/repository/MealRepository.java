package com.example.ancillary_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ancillary_service.model.Meal;

import java.util.List;

public interface MealRepository extends JpaRepository<Meal, Long> {

    List<Meal> findByAirlineId(Long airlineId);

    boolean existsByCodeAndAirlineId(String code, Long airlineId);

    boolean existsByAirlineIdAndCodeAndIdNot(Long airlineId, String code, Long id);

}
