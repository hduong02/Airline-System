package com.example.airline_service.service.impl;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

import com.example.airline_service.mapper.AircraftMapper;
import com.example.airline_service.model.Aircraft;
import com.example.airline_service.model.Airline;
import com.example.airline_service.repository.AircraftRepository;
import com.example.airline_service.repository.AirlineRepository;
import com.example.airline_service.service.AircraftService;
import com.example.payload.request.AircraftRequest;
import com.example.payload.response.AircraftResponse;

@Service
@RequiredArgsConstructor
@Transactional
public class AircraftServiceImpl implements AircraftService {

    private final AircraftRepository aircraftRepository;
    private final AirlineRepository airlineRepository;

    @Override
    public AircraftResponse createAircraft(AircraftRequest request, Long ownerId)
            throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Airline not found for owner: " + ownerId));

        Aircraft aircraft = AircraftMapper.toEntity(request, airline);

        if (aircraftRepository.existsByCode(aircraft.getCode())) {
            throw new Exception(
                    "Aircraft with code " + aircraft.getCode() + " already exists");
        }

        if (aircraft.getSeatingCapacity() != null && aircraft.getSeatingCapacity() <= 0) {
            throw new Exception("Seating capacity must be positive");
        }
        return AircraftMapper.toResponse(aircraftRepository.save(aircraft));
    }

    @Override
    @Cacheable(cacheNames = "aircrafts", key = "#id")
    public AircraftResponse getAircraftById(Long id) throws Exception {
        Aircraft aircraft = aircraftRepository.findById(id)
                .orElseThrow(() -> new Exception("Aircraft not found with id: " + id));
        return AircraftMapper.toResponse(aircraft);
    }

    @Override
    public List<AircraftResponse> listAllAircraftsByOwner(Long ownerId) {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Airline not found for owner: " + ownerId));
        return aircraftRepository.findByAirline(airline)
                .stream()
                .map(AircraftMapper::toResponse)
                .toList();
    }

    @Override
    @CacheEvict(cacheNames = "aircrafts", key = "#id")
    public AircraftResponse updateAircraft(Long id, AircraftRequest request, Long ownerId)
            throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Airline not found for owner: " + ownerId));

        Aircraft aircraft = aircraftRepository.findByIdAndAirlineId(id, airline.getId());
        if (aircraft == null) {
            throw new Exception("Aircraft not found with id: " + id);
        }

        String oldCode = aircraft.getCode();

        if (request.getCode() != null
                && !oldCode.equals(request.getCode())
                && aircraftRepository.existsByCode(request.getCode())) {
            throw new Exception("Aircraft with code " + request.getCode() + " already exists");
        }

        AircraftMapper.updateEntity(aircraft, request);

        return AircraftMapper.toResponse(aircraftRepository.save(aircraft));
    }

    @Override
    @CacheEvict(cacheNames = "aircrafts", key = "#id")
    public void deleteAircraft(Long id, Long ownerId) throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Airline not found for owner: " + ownerId));

        Aircraft aircraft = aircraftRepository.findByIdAndAirlineId(id, airline.getId());
        if (aircraft == null) {
            throw new Exception("Aircraft not found with id: " + id);
        }

        aircraftRepository.delete(aircraft);
    }

}