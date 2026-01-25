package com.example.pricing_service.service;

import java.util.List;

import com.example.payload.request.FareRulesRequest;
import com.example.payload.response.FareRulesResponse;

public interface FareRulesService {

    FareRulesResponse createFareRules(FareRulesRequest request) throws Exception;

    FareRulesResponse getFareRulesById(Long id) throws Exception;

    FareRulesResponse getFareRulesByFareId(Long fareId) throws Exception;

    List<FareRulesResponse> getFareRulesByAirlineId(Long airlineId);

    FareRulesResponse updateFareRules(Long id, FareRulesRequest request) throws Exception;

    void deleteFareRules(Long id) throws Exception;
}
