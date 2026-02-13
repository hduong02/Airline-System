package com.example.ancillary_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ancillary_service.model.InsuranceCoverage;


import java.util.List;

public interface InsuranceCoverageRepository extends JpaRepository<InsuranceCoverage, Long> {

    List<InsuranceCoverage> findByAncillaryId(Long ancillaryId);

    List<InsuranceCoverage> findByAncillaryIdAndActiveTrue(Long ancillaryId);
}
