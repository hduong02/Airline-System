package com.example.pricing_service.service;

import java.util.List;
import java.util.Map;

import com.example.payload.request.FareRequest;
import com.example.payload.response.FareResponse;
import com.example.pricing_service.model.Fare;

public interface FareService {

    FareResponse createFare(FareRequest request) throws Exception;

    FareResponse getFareById(Long id) throws Exception;

    List<FareResponse> getFaresByFlightIdAndCabinClassId(Long flightId, Long cabinClassId);

    FareResponse updateFare(Long id, FareRequest request) throws Exception;

    void deleteFare(Long id) throws Exception;

    List<Fare> getFares();

    Map<Long, FareResponse> getLowestFarePerFlight(List<Long> flightIds, Long cabinClassId);

    FareResponse getLowestFareForFlightAndCabin(Long flightId, Long cabinClassId);

    Map<Long, FareResponse> getFaresByIds(List<Long> ids);
}