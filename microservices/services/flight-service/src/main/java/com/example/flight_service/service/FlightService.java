package com.example.flight_service.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.enums.FlightStatus;
import com.example.payload.request.FlightRequest;
import com.example.payload.response.FlightResponse;

public interface FlightService {

    FlightResponse createFlight(Long userId, FlightRequest request) throws Exception;
    
    FlightResponse getFlightById(Long id);

    Page<FlightResponse> getFlightsByAirline(Long userId,
                                            Long departureAirportId,
                                            Long arrivalAirportId,
                                            Pageable pageable);

    FlightResponse updateFlight(Long id, FlightRequest request) throws Exception;

    FlightResponse changeStatus(Long id, FlightStatus status) throws Exception;

    void deleteFlight(Long userId, Long id) throws Exception;
}
