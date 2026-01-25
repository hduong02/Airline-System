package com.example.pricing_service.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payload.request.FareRequest;
import com.example.payload.response.FareResponse;
import com.example.pricing_service.mapper.FareMapper;
import com.example.pricing_service.model.Fare;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.service.FareService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class FareServiceImpl implements FareService {

    private final FareRepository fareRepository;

    @Override
    public FareResponse createFare(FareRequest request) throws Exception {
        if (fareRepository.existsByFlightIdAndCabinClassIdAndName(
                request.getFlightId(), request.getCabinClassId(), request.getName())) {
            throw new Exception("Fare '" + request.getName()
                    + "' already exists for this flight and cabin class");
        }
        Fare fare = FareMapper.toEntity(request);
        Fare saved = fareRepository.save(fare);
        return FareMapper.toResponse(saved);
    }


    @Override
    public FareResponse getFareById(Long id) throws Exception {
        Fare fare = fareRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare not found with id: " + id));
        return FareMapper.toResponse(fare);
    }

    @Override
    public List<FareResponse> getFaresByFlightIdAndCabinClassId(Long flightId, Long cabinClassId) {
        return fareRepository.findByFlightIdAndCabinClassId(flightId, cabinClassId)
                .stream()
                .map(FareMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public FareResponse updateFare(Long id, FareRequest request) throws Exception {
        Fare existing = fareRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare not found with id: " + id));

        if (fareRepository.existsByFlightIdAndCabinClassIdAndNameAndIdNot(
                request.getFlightId(), request.getCabinClassId(), request.getName(), id)) {
            throw new Exception("Fare '" + request.getName()
                    + "' already exists for this flight and cabin class");
        }

        FareMapper.updateEntity(request, existing);
        Fare saved = fareRepository.save(existing);
        return FareMapper.toResponse(saved);
    }

    @Override
    public void deleteFare(Long id) throws Exception{
        Fare fare = fareRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare not found with id: " + id));
        fareRepository.delete(fare);
    }

    @Override
    public List<Fare> getFares() {
        return fareRepository.findAll();
    }

    @Override
    public Map<Long, FareResponse> getFaresByIds(List<Long> ids) {
        List<Fare> fares = fareRepository.findAllById(ids);
        return fares.stream()
                .collect(Collectors.toMap(Fare::getId, FareMapper::toResponse));
    }

    @Override
    public Map<Long, FareResponse> getLowestFarePerFlight(
            List<Long> flightIds, Long cabinClassId) {
        if (flightIds == null || flightIds.isEmpty()) return Map.of();

        List<Fare> fares = fareRepository.
                findByFlightIdInAndCabinClassId(flightIds, cabinClassId);

        Map<Long,FareResponse> result= fares.stream()
                .collect(Collectors.toMap(
                        Fare::getFlightId,
                        fare -> fare,
                        (existing, candidate) ->
                                candidate.getTotalPrice() < existing.getTotalPrice()
                                        ? candidate : existing
                ))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> FareMapper.toResponse(e.getValue())
                ));
        return result;
    }
}