package com.example.flight_service.service.impl;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.enums.FlightStatus;
import com.example.flight_service.mapper.FlightMapper;
import com.example.flight_service.model.Flight;
import com.example.flight_service.repository.FlightRepository;
import com.example.flight_service.service.FlightService;
import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.payload.request.FlightRequest;
import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.FlightResponse;


@Service
@RequiredArgsConstructor
@Transactional
public class FlightServiceImpl implements FlightService {

    private final FlightRepository flightRepository;
    private final AirlineClient airlineClient;
    private final LocationClient locationClient;

    

    @Override
    public FlightResponse createFlight(Long userId, FlightRequest request) throws Exception {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);

        if (flightRepository.existsByFlightNumber(request.getFlightNumber())) {
            throw new Exception(
                    "Flight with number '" + request.getFlightNumber() + "' already exists");
        }

        Flight flight = FlightMapper.toEntity(request);
        flight.setAirlineId(airlineResponse.getId());
        Flight saved = flightRepository.save(flight);
        return convertToFlightResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightResponse getFlightById(Long id)  {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flight not found with id: " + id));

        return convertToFlightResponse(flight);
    }

    @Override
    public Page<FlightResponse> getFlightsByAirline(
            Long userId,
            Long departureAirportId,
            Long arrivalAirportId,
            Pageable pageable)
    {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);

        return flightRepository.findByAirlineId(
                airlineResponse.getId(),
                departureAirportId,
                arrivalAirportId,
                pageable
        ).map(this::convertToFlightResponse);
    }

    @Override
    public FlightResponse updateFlight(Long id, FlightRequest request) throws Exception {
        Flight existing = flightRepository.findById(id)
                .orElseThrow(() -> new Exception("Flight not found with id: " + id));

        if (request.getFlightNumber() != null &&
                flightRepository.existsByFlightNumberAndIdNot(request.getFlightNumber(), id)) {
            throw new Exception(
                    "Flight with number '" + request.getFlightNumber() + "' already exists");
        }

        FlightMapper.updateEntity(request, existing);
        Flight saved = flightRepository.save(existing);
        return convertToFlightResponse(saved);
    }

    @Override
    public FlightResponse changeStatus(Long id, FlightStatus status) throws Exception {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new Exception("Flight not found with id: " + id));
        flight.setStatus(status);
        Flight updated = flightRepository.save(flight);
        return convertToFlightResponse(updated);
    }

    @Override
    public void deleteFlight(Long userId, Long id) throws Exception {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);

        Flight flight = flightRepository.findByAirlineIdAndId(airlineResponse.getId(), id)
                .orElseThrow(() -> new Exception("Flight not found with id: " + id));
        flightRepository.delete(flight);
    }

    private FlightResponse convertToFlightResponse(Flight flight) {
        //service to service communication
        
        AircraftResponse aircraft = airlineClient.getAircraftById(flight.getAircraftId());
        AirlineResponse airline = airlineClient.getAirlineById(flight.getAirlineId());
        AirportResponse departureAirport = locationClient.getAirportById(
                flight.getDepartureAirportId());
        AirportResponse arrivalAirport = locationClient.getAirportById(
                flight.getArrivalAirportId());
        return FlightMapper.toResponse(
                flight,
                aircraft,
                airline,
                departureAirport,
                arrivalAirport);
    }
}