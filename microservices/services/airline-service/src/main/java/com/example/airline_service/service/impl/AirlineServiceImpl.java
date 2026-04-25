package com.example.airline_service.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.airline_service.mapper.AirlineMapper;
import com.example.airline_service.model.Airline;
import com.example.airline_service.repository.AirlineRepository;
import com.example.airline_service.service.AirlineService;
import com.example.enums.AirlineStatus;
import com.example.payload.request.AirlineRequest;
import com.example.payload.response.AirlineDropdownItem;
import com.example.payload.response.AirlineResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AirlineServiceImpl implements AirlineService {

    private final AirlineRepository airlineRepository;

    // ---------- CRUD ----------

    @Override
    public AirlineResponse createAirline(AirlineRequest request, Long ownerId) {
        Airline airline = AirlineMapper.toEntity(request, ownerId);
        Airline saved = airlineRepository.save(airline);
        return AirlineMapper.toResponse(saved);
    }

    @Override
    @Cacheable(cacheNames = "airlinesByOwner", key = "#ownerId")
    public AirlineResponse getAirlineByOwner(Long ownerId) throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new Exception(
                        "Airline not found for owner: " + ownerId));
        return AirlineMapper.toResponse(airline);
    }

    @Override
    @Cacheable(cacheNames = "airlines", key = "#id")
    public AirlineResponse getAirlineById(Long id) throws Exception {
        Airline airline = airlineRepository.findById(id)
                .orElseThrow(() -> new Exception("Airline not found"));
        return AirlineMapper.toResponse(airline);
    }

    @Override
    public Page<AirlineResponse> getAllAirlines(Pageable pageable) {
        return airlineRepository
                .findAll(pageable).map(AirlineMapper::toResponse);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "airlinesByOwner", key = "#ownerId"),
            @CacheEvict(cacheNames = "airlines", allEntries = true),
            @CacheEvict(cacheNames = "airlinesByIata", allEntries = true),
            @CacheEvict(cacheNames = "airlinesByAlliance", allEntries = true)
    })
    public AirlineResponse updateAirline(AirlineRequest request, Long ownerId)
            throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new Exception(
                        "Airline not found for owner: " + ownerId));

        AirlineMapper.updateEntity(airline, request);
        return AirlineMapper.toResponse(airlineRepository.save(airline));
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "airlines", key = "#id"),
            @CacheEvict(cacheNames = "airlinesByOwner", allEntries = true),
            @CacheEvict(cacheNames = "airlinesByIata", allEntries = true),
            @CacheEvict(cacheNames = "airlinesByAlliance", allEntries = true)
    })
    public void deleteAirline(Long id, Long ownerId) throws Exception {
        Airline airline = airlineRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new Exception(
                        "Airline not found for owner: " + ownerId));
        airlineRepository.delete(airline);
    }

    // ---------- Business Operations ----------

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "airlines", key = "#airlineId"),
            @CacheEvict(cacheNames = "airlinesByAlliance", allEntries = true)
    })
    public AirlineResponse changeStatusByAdmin(Long airlineId, AirlineStatus status)
            throws Exception {
        Airline airline = airlineRepository.findById(airlineId)
                .orElseThrow(() -> new Exception(
                        "Airline not found with ID: " + airlineId));
        airline.setStatus(status);
        return AirlineMapper.toResponse(airlineRepository.save(airline));
    }

    // ---------- Search / Filters ----------

    @Override
    @Transactional(readOnly = true)
    public List<AirlineDropdownItem> getAirlinesForDropdown() {
        return airlineRepository.findByStatus(AirlineStatus.ACTIVE).stream()
                .map(a -> AirlineDropdownItem.builder()
                        .id(a.getId())
                        .name(a.getName())
                        .iataCode(a.getIataCode())
                        .icaoCode(a.getIcaoCode())
                        .logoUrl(a.getLogoUrl())
                        .country(a.getCountry())
                        .build())
                .toList();
    }


}
