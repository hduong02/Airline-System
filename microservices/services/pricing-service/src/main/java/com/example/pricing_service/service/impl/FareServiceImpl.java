package com.example.pricing_service.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.enums.CabinClassType;
import com.example.payload.request.FareRequest;
import com.example.payload.response.CabinClassResponse;
import com.example.payload.response.FareResponse;
import com.example.pricing_service.client.SeatClient;
import com.example.pricing_service.mapper.FareMapper;
import com.example.pricing_service.model.Fare;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.service.FareService;
import feign.FeignException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class FareServiceImpl implements FareService {

    private final FareRepository fareRepository;
    private final SeatClient seatClient;

    @Override
    public FareResponse createFare(FareRequest request) throws Exception {
        if (fareRepository.existsByFlightIdAndCabinClassIdAndName(
                request.getFlightId(), request.getCabinClassId(), request.getName())) {
            throw new Exception("Fare '" + request.getName()
                    + "' already exists for this flight and cabin class");
        }
        Fare fare = FareMapper.toEntity(request);
        fare.setCabinClass(resolveCabinClass(fare.getCabinClassId()));
        Fare saved = fareRepository.save(fare);
        return FareMapper.toResponse(saved);
    }


    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "fares", key = "#id")
    public FareResponse getFareById(Long id) throws Exception {
        Fare fare = fareRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare not found with id: " + id));
        return toFareResponse(fare);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FareResponse> getFaresByFlightIdAndCabinClassId(Long flightId, Long cabinClassId) {
        return fareRepository.findByFlightIdAndCabinClassId(flightId, cabinClassId)
                .stream()
                .map(this::toFareResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "fares", key = "#id"),
            @CacheEvict(cacheNames = "faresByFlight", allEntries = true)
    })
    public FareResponse updateFare(Long id, FareRequest request) throws Exception {
        Fare existing = fareRepository.findById(id)
                .orElseThrow(() -> new Exception("Fare not found with id: " + id));

        if (fareRepository.existsByFlightIdAndCabinClassIdAndNameAndIdNot(
                request.getFlightId(), request.getCabinClassId(), request.getName(), id)) {
            throw new Exception("Fare '" + request.getName()
                    + "' already exists for this flight and cabin class");
        }

        Long cabinClassId = request.getCabinClassId() != null
                ? request.getCabinClassId() : existing.getCabinClassId();
        CabinClassType cabinClass = resolveCabinClass(cabinClassId);
        FareMapper.updateEntity(request, existing);
        existing.setCabinClass(cabinClass);
        Fare saved = fareRepository.save(existing);
        return FareMapper.toResponse(saved);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "fares", key = "#id"),
            @CacheEvict(cacheNames = "faresByFlight", allEntries = true)
    })
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
    @Transactional(readOnly = true)
    public Map<Long, FareResponse> getFaresByIds(List<Long> ids) {
        List<Fare> fares = fareRepository.findAllById(ids);
        return fares.stream()
                .collect(Collectors.toMap(Fare::getId, this::toFareResponse));
    }

    @Override
    @Transactional(readOnly = true)
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
                        e -> toFareResponse(e.getValue())
                ));
        return result;
    }


    @Override
    public FareResponse getLowestFareForFlightAndCabin(Long flightId, Long cabinClassId) {

        List<Fare> fares = fareRepository.findByFlightIdAndCabinClassId(
                flightId,
                cabinClassId
        );

        Fare lowestFare = fares.stream()
                .min(Comparator.comparingDouble(Fare::getTotalPrice))
                .orElse(null);

        return toFareResponse(lowestFare);
    }

    private FareResponse toFareResponse(Fare fare) {
        FareResponse response = FareMapper.toResponse(fare);
        if (response != null && response.getCabinClass() == null) {
            response.setCabinClass(resolveCabinClass(fare.getCabinClassId()));
        }
        return response;
    }

    private CabinClassType resolveCabinClass(Long cabinClassId) {
        if (cabinClassId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cabin class ID is required");
        }
        CabinClassResponse cabin;
        try {
            cabin = seatClient.getCabinClassById(cabinClassId);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cabin class does not exist", e);
        }
        if (cabin == null || cabin.getName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cabin class has no valid type");
        }
        try {
            return CabinClassType.valueOf(cabin.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cabin class has no valid type", e);
        }
    }
}
