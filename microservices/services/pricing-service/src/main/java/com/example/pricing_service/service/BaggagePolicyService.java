package com.example.pricing_service.service;

import com.example.payload.request.BaggagePolicyRequest;
import com.example.payload.response.BaggagePolicyResponse;

import java.util.List;

public interface BaggagePolicyService {

    BaggagePolicyResponse createBaggagePolicy(BaggagePolicyRequest request) throws Exception;
    
    BaggagePolicyResponse getBaggagePolicyById(Long id) throws Exception;

    BaggagePolicyResponse getBaggagePolicyByFareId(Long fareId) throws Exception;

    List<BaggagePolicyResponse> getBaggagePoliciesByAirlineId(Long airlineId);

    BaggagePolicyResponse updateBaggagePolicy(Long id, BaggagePolicyRequest request) throws Exception;

    void deleteBaggagePolicy(Long id) throws Exception;
}
