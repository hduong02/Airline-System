package com.example.ancillary_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.ancillary_service.mapper.FlightCabinAncillaryMapper;
import com.example.ancillary_service.mapper.InsuranceCoverageMapper;
import com.example.ancillary_service.model.Ancillary;
import com.example.ancillary_service.model.FlightCabinAncillary;
import com.example.ancillary_service.model.InsuranceCoverage;
import com.example.ancillary_service.repository.AncillaryRepository;
import com.example.ancillary_service.repository.FlightCabinAncillaryRepository;
import com.example.ancillary_service.repository.InsuranceCoverageRepository;
import com.example.ancillary_service.service.FlightCabinAncillaryService;
import com.example.enums.AncillaryType;
import com.example.payload.request.FlightCabinAncillaryRequest;
import com.example.payload.response.FlightCabinAncillaryResponse;
import com.example.payload.response.InsuranceCoverageResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlightCabinAncillaryServiceImpl implements FlightCabinAncillaryService {

    private final FlightCabinAncillaryRepository flightCabinAncillaryRepository;
    private final AncillaryRepository ancillaryRepository;
    private final InsuranceCoverageRepository insuranceCoverageRepository;

    @Override
    public FlightCabinAncillaryResponse create(FlightCabinAncillaryRequest request)
            throws Exception {
        Ancillary ancillary = ancillaryRepository.findById(request.getAncillaryId())
                .orElseThrow(() -> new Exception("Ancillary not found"));

        FlightCabinAncillary entity = FlightCabinAncillary.builder()
                .flightId(request.getFlightId())
                .cabinClassId(request.getCabinClassId())
                .ancillary(ancillary)
                .available(request.getAvailable())
                .maxQuantity(request.getMaxQuantity())
                .price(request.getPrice())
                .includedInFare(request.getIncludedInFare())
                .build();

        FlightCabinAncillary saved = flightCabinAncillaryRepository.save(entity);
        return convertToResponse(saved);
    }

    @Override
    public FlightCabinAncillaryResponse getById(Long id) throws Exception {
        FlightCabinAncillary entity = flightCabinAncillaryRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightCabinAncillary not found"));
        return convertToResponse(entity);
    }

    @Override
    public List<FlightCabinAncillaryResponse> getByFlightAndCabinClass(
            Long flightId, Long cabinClassId) {
        return flightCabinAncillaryRepository.findByFlightIdAndCabinClassId(
                flightId, cabinClassId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<FlightCabinAncillaryResponse> getAllByIds(List<Long> ids) {
        List<FlightCabinAncillary> ancillaries = flightCabinAncillaryRepository
                .findAllById(ids);
        return ancillaries.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }


    @Override
    public FlightCabinAncillaryResponse getByFlightIdAndCabinClassIdAndType(
            Long flightId, Long cabinClassId, AncillaryType type) throws Exception {
        FlightCabinAncillary entity = flightCabinAncillaryRepository
                .findByFlightIdAndCabinClassIdAndAncillaryType(flightId, cabinClassId, type)
                .orElseThrow(() -> new Exception(
                        "FlightCabinAncillary not found for type: " + type));
        return convertToResponse(entity);
    }

    @Override
    public List<FlightCabinAncillaryResponse> getAllByFlightIdAndCabinClassIdAndType(
            Long flightId, Long cabinClassId, AncillaryType type) throws Exception {
        List<FlightCabinAncillary> ancillaries =
                flightCabinAncillaryRepository.findAllByFlightIdAndCabinClassIdAndAncillaryType(
                        flightId, cabinClassId, type);
        return ancillaries.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }


    @Override
    public FlightCabinAncillaryResponse update(Long id, FlightCabinAncillaryRequest req)
            throws Exception {
        FlightCabinAncillary entity = flightCabinAncillaryRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightCabinAncillary not found"));

        entity.setAvailable(req.getAvailable());
        entity.setMaxQuantity(req.getMaxQuantity());
        entity.setPrice(req.getPrice());
        entity.setIncludedInFare(req.getIncludedInFare());

        return convertToResponse(flightCabinAncillaryRepository.save(entity));
    }

    @Override
    public void delete(Long id) throws Exception {
        FlightCabinAncillary entity = flightCabinAncillaryRepository.findById(id)
                .orElseThrow(() -> new Exception("FlightCabinAncillary not found"));
        flightCabinAncillaryRepository.delete(entity);
    }

    @Override
    public Double calculateAncillaryPrice(List<Long> ancillaryIds) {
        List<FlightCabinAncillary> ancillaries = flightCabinAncillaryRepository
                .findAllById(ancillaryIds);

        double totalPrice = 0;
        for (FlightCabinAncillary ancillary : ancillaries) {
            totalPrice += ancillary.getPrice();
        }
        return totalPrice;
    }

    private FlightCabinAncillaryResponse convertToResponse(FlightCabinAncillary ancillary) {
        List<InsuranceCoverage> coverages = insuranceCoverageRepository
                .findByAncillaryId(ancillary.getId());
        List<InsuranceCoverageResponse> coverageResponses = coverages.stream()
                .map(InsuranceCoverageMapper::toResponse)
                .toList();
        return FlightCabinAncillaryMapper.toResponse(ancillary, coverageResponses);
    }
}