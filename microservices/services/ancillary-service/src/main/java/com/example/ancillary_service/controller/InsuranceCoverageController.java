package com.example.ancillary_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.ancillary_service.service.InsuranceCoverageService;
import com.example.payload.request.InsuranceCoverageRequest;
import com.example.payload.response.ApiResponse;
import com.example.payload.response.InsuranceCoverageResponse;

import java.util.List;

@RestController
@RequestMapping("/api/insurance-coverages")
@RequiredArgsConstructor
public class InsuranceCoverageController {

    private final InsuranceCoverageService InsuranceCoverageService;

    @PostMapping
    public ResponseEntity<InsuranceCoverageResponse> createCoverage(
            @Valid @RequestBody InsuranceCoverageRequest request) throws Exception {
        InsuranceCoverageResponse response = InsuranceCoverageService.createCoverage(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<InsuranceCoverageResponse> updateCoverage(
            @PathVariable Long id,
            @RequestBody InsuranceCoverageRequest request) throws Exception {
        return ResponseEntity.ok(InsuranceCoverageService.updateCoverage(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteCoverage(@PathVariable Long id)
            throws Exception {
        InsuranceCoverageService.deleteCoverage(id);
        return ResponseEntity.ok(new ApiResponse("Coverage deleted successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InsuranceCoverageResponse> getCoverageById(@PathVariable Long id)
            throws Exception {
        return ResponseEntity.ok(InsuranceCoverageService.getCoverageById(id));
    }

    @GetMapping
    public ResponseEntity<List<InsuranceCoverageResponse>> getAllCoverages() {
        return ResponseEntity.ok(InsuranceCoverageService.getAllCoverages());
    }

    @GetMapping("/ancillary/{ancillaryId}")
    public ResponseEntity<List<InsuranceCoverageResponse>> getCoveragesByAncillaryId(
            @PathVariable Long ancillaryId) {
        return ResponseEntity.ok(InsuranceCoverageService
                .getCoveragesByAncillaryId(ancillaryId));
    }

    @GetMapping("/ancillary/{ancillaryId}/active")
    public ResponseEntity<List<InsuranceCoverageResponse>> getActiveCoveragesByAncillaryId(
            @PathVariable Long ancillaryId) {
        return ResponseEntity.ok(InsuranceCoverageService
                .getActiveCoveragesByAncillaryId(ancillaryId));
    }
}
