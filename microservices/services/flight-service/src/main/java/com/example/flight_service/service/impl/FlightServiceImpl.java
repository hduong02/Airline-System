package com.example.flight_service.service.impl;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    @Transactional(readOnly = true)
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
    public FlightResponse updateFlight(Long userId, Long id, FlightRequest request) throws Exception {
        Flight existing = getOwnedFlight(userId, id);

        if (request.getAirlineId() != null && !request.getAirlineId().equals(existing.getAirlineId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A flight cannot be transferred to another airline");
        }

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
    public FlightResponse changeStatus(Long userId, Long id, FlightStatus status) throws Exception {
        Flight flight = getOwnedFlight(userId, id);
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

    private Flight getOwnedFlight(Long userId, Long id) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Flight not found with id: " + id));
        AirlineResponse airline = airlineClient.getAirlineById(flight.getAirlineId());
        if (userId == null || airline == null || !userId.equals(airline.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not own this flight's airline");
        }
        return flight;
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
