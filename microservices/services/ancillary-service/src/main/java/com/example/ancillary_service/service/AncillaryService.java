package com.example.ancillary_service.service;

import java.util.List;

import com.example.payload.request.AncillaryRequest;
import com.example.payload.response.AncillaryResponse;

public interface AncillaryService {

    AncillaryResponse create(Long airlineId, AncillaryRequest request);

    AncillaryResponse getById(Long id) throws Exception;

    List<AncillaryResponse> getByAirlineId(Long airlineId);

    AncillaryResponse updateAncillary(Long id, AncillaryRequest request) throws Exception;

    void deleteAncillary(Long id) throws Exception;
}
