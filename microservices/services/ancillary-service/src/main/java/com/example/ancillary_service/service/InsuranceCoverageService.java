package com.example.ancillary_service.service;

import java.util.List;

import com.example.payload.request.InsuranceCoverageRequest;
import com.example.payload.response.InsuranceCoverageResponse;

public interface InsuranceCoverageService {

    InsuranceCoverageResponse createCoverage(InsuranceCoverageRequest request) throws Exception;

    InsuranceCoverageResponse updateCoverage(Long id, InsuranceCoverageRequest request) throws Exception;

    void deleteCoverage(Long id) throws Exception;

    InsuranceCoverageResponse getCoverageById(Long id) throws Exception;

    List<InsuranceCoverageResponse> getCoveragesByAncillaryId(Long ancillaryId);

    List<InsuranceCoverageResponse> getActiveCoveragesByAncillaryId(Long ancillaryId);

    List<InsuranceCoverageResponse> getAllCoverages();
}