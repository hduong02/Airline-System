package com.example.pricing_service.service.impl;

import com.example.pricing_service.mapper.BaggagePolicyMapper;
import com.example.pricing_service.model.BaggagePolicy;
import com.example.pricing_service.model.Fare;
import com.example.pricing_service.repository.BaggagePolicyRepository;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.service.BaggagePolicyService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payload.request.BaggagePolicyRequest;
import com.example.payload.response.BaggagePolicyResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class BaggagePolicyServiceImpl implements BaggagePolicyService {

    private final BaggagePolicyRepository baggagePolicyRepository;
    private final FareRepository fareRepository;

    @Override
    public BaggagePolicyResponse createBaggagePolicy(
            BaggagePolicyRequest request) throws Exception {
        Fare fare = fareRepository.findById(request.getFareId())
                .orElseThrow(() -> new Exception(
                        "Fare not found with id: " + request.getFareId()));

        if (baggagePolicyRepository.existsByFareId(request.getFareId())) {
            throw new Exception(
                    "Baggage policy already exists for fare id: " + request.getFareId());
        }

        BaggagePolicy policy = BaggagePolicyMapper.toEntity(request, fare);
        BaggagePolicy saved = baggagePolicyRepository.save(policy);
        return BaggagePolicyMapper.toResponse(saved);
    }

    @Override
    public BaggagePolicyResponse getBaggagePolicyById(Long id) throws Exception {
        BaggagePolicy policy = baggagePolicyRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Baggage policy not found with id: " + id));
        return BaggagePolicyMapper.toResponse(policy);
    }

    @Override
    public BaggagePolicyResponse getBaggagePolicyByFareId(Long fareId) throws Exception {
        BaggagePolicy policy = baggagePolicyRepository.findByFareId(fareId)
                .orElseThrow(() -> new Exception(
                        "Baggage policy not found for fare id: " + fareId));
        return BaggagePolicyMapper.toResponse(policy);
    }

    @Override
    public List<BaggagePolicyResponse> getBaggagePoliciesByAirlineId(Long airlineId) {
        return baggagePolicyRepository.findByAirlineId(airlineId).stream()
                .map(BaggagePolicyMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public BaggagePolicyResponse updateBaggagePolicy(
            Long id, BaggagePolicyRequest request) throws Exception {
        BaggagePolicy existing = baggagePolicyRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Baggage policy not found with id: " + id));

        BaggagePolicyMapper.updateEntity(request, existing);
        BaggagePolicy saved = baggagePolicyRepository.save(existing);
        return BaggagePolicyMapper.toResponse(saved);
    }

    @Override
    public void deleteBaggagePolicy(Long id) throws Exception {
        BaggagePolicy policy = baggagePolicyRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Baggage policy not found with id: " + id));
        baggagePolicyRepository.delete(policy);
    }
}
