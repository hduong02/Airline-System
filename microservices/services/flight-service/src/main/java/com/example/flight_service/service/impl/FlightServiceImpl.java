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

    @Override
    public FlightResponse createFlight(Long airlineId, FlightRequest request) throws Exception {
        if (flightRepository.existsByFlightNumber(request.getFlightNumber())) {
            throw new Exception(
                    "Flight with number '" + request.getFlightNumber() + "' already exists");
        }

        Flight flight = FlightMapper.toEntity(request);
        flight.setAirlineId(airlineId);
        Flight saved = flightRepository.save(flight);
        return getFlightResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightResponse getFlightById(Long id)  {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flight not found with id: " + id));

        return getFlightResponse(flight);
    }

    @Override
    public Page<FlightResponse> getFlightsByAirline(Long airlineId,
                                                    Long departureAirportId,
                                                    Long arrivalAirportId,
                                                    Pageable pageable) {
        return flightRepository.findByAirlineId(
                airlineId,
                departureAirportId,
                arrivalAirportId,
                pageable
        ).map(this::getFlightResponse);
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
        return getFlightResponse(saved);
    }

    @Override
    public FlightResponse changeStatus(Long id, FlightStatus status) throws Exception {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new Exception("Flight not found with id: " + id));
        flight.setStatus(status);
        Flight updated = flightRepository.save(flight);
        return getFlightResponse(updated);
    }

    @Override
    public void deleteFlight(Long airlineId, Long id) throws Exception {
        Flight flight = flightRepository.findByAirlineIdAndId(airlineId, id)
                .orElseThrow(() -> new Exception("Flight not found with id: " + id));
        flightRepository.delete(flight);
    }

    private FlightResponse getFlightResponse(Flight flight) {
        AircraftResponse aircraft = AircraftResponse.builder()
                .id(flight.getAircraftId())
                .build();
        AirlineResponse airline = AirlineResponse.builder()
                .id(flight.getAirlineId())
                .build();
        AirportResponse departureAirport = AirportResponse.builder()
                .id(flight.getDepartureAirportId())
                .build();
        AirportResponse arrivalAirport = AirportResponse.builder()
                .id(flight.getArrivalAirportId())
                .build();
        return FlightMapper.toResponse(flight, aircraft, airline,
                departureAirport, arrivalAirport);
    }
}