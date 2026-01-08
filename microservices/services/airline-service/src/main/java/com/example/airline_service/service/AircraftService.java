package com.example.airline_service.service;

import java.util.List;

import com.example.payload.request.AircraftRequest;
import com.example.payload.response.AircraftResponse;

public interface AircraftService {

    AircraftResponse getAircraftById(Long id) throws Exception;

    List<AircraftResponse> listAllAircraftsByOwner(Long ownerId);

    AircraftResponse createAircraft(AircraftRequest request, Long ownerId) throws Exception;

    AircraftResponse updateAircraft(Long id, AircraftRequest request, Long ownerId)
            throws Exception;

    void deleteAircraft(Long id, Long ownerId) throws Exception;
}

