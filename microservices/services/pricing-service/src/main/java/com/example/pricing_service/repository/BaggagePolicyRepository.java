package com.example.pricing_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.pricing_service.model.BaggagePolicy;

import java.util.List;
import java.util.Optional;

public interface BaggagePolicyRepository extends JpaRepository<BaggagePolicy, Long> {

    Optional<BaggagePolicy> findByFareId(Long fareId);

    List<BaggagePolicy> findByAirlineId(Long airlineId);

    boolean existsByFareId(Long fareId);

}
