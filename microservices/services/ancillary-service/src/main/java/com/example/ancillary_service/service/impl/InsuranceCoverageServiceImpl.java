package com.example.ancillary_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.ancillary_service.mapper.InsuranceCoverageMapper;
import com.example.ancillary_service.model.Ancillary;
import com.example.ancillary_service.model.InsuranceCoverage;
import com.example.ancillary_service.repository.AncillaryRepository;
import com.example.ancillary_service.repository.InsuranceCoverageRepository;
import com.example.ancillary_service.service.InsuranceCoverageService;
import com.example.payload.request.InsuranceCoverageRequest;
import com.example.payload.response.InsuranceCoverageResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InsuranceCoverageServiceImpl implements InsuranceCoverageService {

    private final InsuranceCoverageRepository insuranceCoverageRepository;
    private final AncillaryRepository ancillaryRepository;

    @Override
    public InsuranceCoverageResponse createCoverage(InsuranceCoverageRequest request)
            throws Exception {
        Ancillary ancillary = ancillaryRepository.findById(request.getAncillaryId())
                .orElseThrow(() -> new Exception(
                        "Ancillary not found with ID: " + request.getAncillaryId()));

        InsuranceCoverage coverage = InsuranceCoverageMapper.toEntity(request, ancillary);
        InsuranceCoverage saved = insuranceCoverageRepository.save(coverage);
        return InsuranceCoverageMapper.toResponse(saved);
    }

    @Override
    public InsuranceCoverageResponse updateCoverage(Long id,
            InsuranceCoverageRequest request) throws Exception {
        InsuranceCoverage existing = insuranceCoverageRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Insurance coverage not found with ID: " + id));

        Ancillary ancillary = null;
        if (request.getAncillaryId() != null) {
            ancillary = ancillaryRepository.findById(request.getAncillaryId())
                    .orElseThrow(() -> new Exception(
                            "Ancillary not found with ID: " + request.getAncillaryId()));
        }

        InsuranceCoverageMapper.updateEntityFromRequest(existing, request, ancillary);
        InsuranceCoverage updated = insuranceCoverageRepository.save(existing);
        return InsuranceCoverageMapper.toResponse(updated);
    }

    @Override
    public void deleteCoverage(Long id) throws Exception {
        InsuranceCoverage coverage = insuranceCoverageRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Insurance coverage not found with ID: " + id));
        insuranceCoverageRepository.delete(coverage);
    }

    @Override
    public InsuranceCoverageResponse getCoverageById(Long id) throws Exception {
        InsuranceCoverage coverage = insuranceCoverageRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Insurance coverage not found with ID: " + id));
        return InsuranceCoverageMapper.toResponse(coverage);
    }

    @Override
    public List<InsuranceCoverageResponse> getCoveragesByAncillaryId(Long ancillaryId) {
        return insuranceCoverageRepository.findByAncillaryId(ancillaryId)
                .stream()
                .map(InsuranceCoverageMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<InsuranceCoverageResponse> getActiveCoveragesByAncillaryId(Long ancillaryId) {
        return insuranceCoverageRepository.findByAncillaryIdAndActiveTrue(ancillaryId).stream()
                .map(InsuranceCoverageMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<InsuranceCoverageResponse> getAllCoverages() {
        return insuranceCoverageRepository.findAll().stream()
                .map(InsuranceCoverageMapper::toResponse)
                .collect(Collectors.toList());
    }
}