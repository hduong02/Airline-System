package com.example.flight_service.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.payload.request.FlightSearchRequest;
import com.example.payload.response.FlightInstanceResponse;


public interface FlightSearchService {

    Page<FlightInstanceResponse> searchFlights(
            FlightSearchRequest request,
            Pageable pageable
    );

}
