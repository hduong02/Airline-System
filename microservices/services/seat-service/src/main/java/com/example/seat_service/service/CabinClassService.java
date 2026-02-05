package com.example.seat_service.service;

import com.example.enums.CabinClassType;
import com.example.payload.request.CabinClassRequest;
import com.example.payload.response.CabinClassResponse;

import java.util.List;

public interface CabinClassService {

    CabinClassResponse createCabinClass(CabinClassRequest request) throws Exception;

    CabinClassResponse getCabinClassById(Long id) throws Exception;

    List<CabinClassResponse> getCabinClassesByAircraftId(Long aircraftId);

    CabinClassResponse getByAircraftIdAndName(Long aircraftId, CabinClassType name);

    CabinClassResponse updateCabinClass(Long id, CabinClassRequest request) throws Exception;

    void deleteCabinClass(Long id) throws Exception;
}