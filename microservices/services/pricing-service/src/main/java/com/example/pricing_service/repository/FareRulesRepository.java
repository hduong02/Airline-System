package com.example.pricing_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.pricing_service.model.FareRules;

import java.util.List;
import java.util.Optional;

public interface FareRulesRepository extends JpaRepository<FareRules, Long> {

    Optional<FareRules> findByFareId(Long fareId);

    List<FareRules> findByAirlineId(Long airlineId);

    boolean existsByFareId(Long fareId);
}
