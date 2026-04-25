package com.example.pricing_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payload.request.FareRulesRequest;
import com.example.payload.response.FareRulesResponse;
import com.example.pricing_service.mapper.FareRulesMapper;
import com.example.pricing_service.model.Fare;
import com.example.pricing_service.model.FareRules;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.repository.FareRulesRepository;
import com.example.pricing_service.service.FareRulesService;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class FareRulesServiceImpl implements FareRulesService {

    private final FareRulesRepository fareRulesRepository;
    private final FareRepository fareRepository;

    @Override
    public FareRulesResponse createFareRules(FareRulesRequest request) throws Exception {
        Fare fare = fareRepository.findById(request.getFareId())
                .orElseThrow(() -> new Exception(
                        "Fare not found with id: " + request.getFareId()));

        if (fareRulesRepository.existsByFareId(request.getFareId())) {
            throw new Exception(
                    "Fare rules already exist for fare id: " + request.getFareId());
        }

        FareRules fareRules = FareRulesMapper.toEntity(request, fare);
        FareRules saved = fareRulesRepository.save(fareRules);
        return FareRulesMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FareRulesResponse getFareRulesById(Long id) throws Exception {
        FareRules fareRules = fareRulesRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare rules not found with id: " + id));
        return FareRulesMapper.toResponse(fareRules);
    }

    @Override
    @Transactional(readOnly = true)
    public FareRulesResponse getFareRulesByFareId(Long fareId) throws Exception {
        FareRules fareRules = fareRulesRepository.findByFareId(fareId)
                .orElseThrow(() -> new Exception("Fare rules not found for fare id: " + fareId));
        return FareRulesMapper.toResponse(fareRules);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FareRulesResponse> getFareRulesByAirlineId(Long airlineId) {
        return fareRulesRepository.findByAirlineId(airlineId).stream()
                .map(FareRulesMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public FareRulesResponse updateFareRules(Long id, FareRulesRequest request)
            throws Exception {
        FareRules existing = fareRulesRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare rules not found with id: " + id));

        FareRulesMapper.updateEntity(request, existing);
        FareRules saved = fareRulesRepository.save(existing);
        return FareRulesMapper.toResponse(saved);
    }

    @Override
    public void deleteFareRules(Long id) throws Exception {
        FareRules fareRules = fareRulesRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare rules not found with id: " + id));
        fareRulesRepository.delete(fareRules);
    }
}
