package com.example.location_service.service;

import java.util.List;

import com.example.payload.request.AirportRequest;
import com.example.payload.response.AirportResponse;

public interface AirportService {

    AirportResponse createAirport(AirportRequest request) throws Exception;
    AirportResponse getAirportById(Long id) throws Exception;

    List<AirportResponse> getAllAirports();

    AirportResponse updateAirport(Long id, AirportRequest request) throws Exception;
    void deleteAirport(Long id) throws Exception;
    List<AirportResponse> getAirportsByCityId(Long cityId);
}
