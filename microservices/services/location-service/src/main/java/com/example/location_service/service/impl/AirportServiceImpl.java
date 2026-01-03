package com.example.location_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.location_service.mapper.AirportMapper;
import com.example.location_service.model.Airport;
import com.example.location_service.model.City;
import com.example.location_service.repository.AirportRepository;
import com.example.location_service.repository.CityRepository;
import com.example.location_service.service.AirportService;
import com.example.payload.request.AirportRequest;
import com.example.payload.response.AirportResponse;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AirportServiceImpl implements AirportService {

    private final AirportRepository airportRepository;
    private final CityRepository cityRepository;

    @Override
    @Transactional
    public AirportResponse createAirport(AirportRequest request) throws Exception {
        if (airportRepository.findByIataCode(request.getIataCode()).isPresent()) {
            throw new Exception(
                    "Airport with IATA code " + request.getIataCode() + " already exists.");
        }

        City city = cityRepository.findById(request.getCityId())
                .orElseThrow(() -> new Exception(
                        "City not found with id: " + request.getCityId()));

        Airport airport = AirportMapper.toEntity(request);
        airport.setCity(city);

        Airport savedAirport = airportRepository.save(airport);
        return AirportMapper.toResponse(savedAirport);
    }

    @Override
    public AirportResponse getAirportById(Long id) throws Exception {
        Airport airport = airportRepository.findById(id)
                .orElseThrow(() -> new Exception("Airport not found with id: " + id));
        return AirportMapper.toResponse(airport);
    }


    @Override
    public List<AirportResponse> getAllAirports() {
        return airportRepository.findAll().stream()
                .map(AirportMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AirportResponse updateAirport(Long id, AirportRequest request) throws Exception {
        Airport existingAirport = airportRepository.findById(id)
                .orElseThrow(() -> new Exception("Airport not found with id: " + id));

        if (request.getIataCode() != null
                && !existingAirport.getIataCode().equals(request.getIataCode())
                && airportRepository.findByIataCode(request.getIataCode()).isPresent()) {
            throw new Exception("IATA code " + request.getIataCode() + " is already taken.");
        }

        AirportMapper.updateEntity(request, existingAirport);

        Airport updatedAirport = airportRepository.save(existingAirport);
        return AirportMapper.toResponse(updatedAirport);
    }

    @Override
    public void deleteAirport(Long id) throws Exception {
        Airport airport = airportRepository.findById(id)
                .orElseThrow(() -> new Exception("Airport not found with id: " + id));
        airportRepository.delete(airport);
    }

    @Override
    public List<AirportResponse> getAirportsByCityId(Long cityId) {
        return airportRepository.findByCityId(cityId).stream()
                .map(AirportMapper::toResponse)
                .collect(Collectors.toList());
    }
}
