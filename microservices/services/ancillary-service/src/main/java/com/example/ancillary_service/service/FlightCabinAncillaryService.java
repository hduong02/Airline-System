package com.example.ancillary_service.service;

import java.util.List;

import com.example.enums.AncillaryType;
import com.example.payload.request.FlightCabinAncillaryRequest;
import com.example.payload.response.FlightCabinAncillaryResponse;

public interface FlightCabinAncillaryService {

    FlightCabinAncillaryResponse create(FlightCabinAncillaryRequest request)
            throws Exception;

    FlightCabinAncillaryResponse getById(Long id) throws Exception;

    List<FlightCabinAncillaryResponse> getByFlightAndCabinClass(
            Long flightId, Long cabinClassId);

    List<FlightCabinAncillaryResponse> getAllByIds(List<Long> ids);

    FlightCabinAncillaryResponse getByFlightIdAndCabinClassIdAndType(
            Long flightId, Long cabinClassId, AncillaryType type) throws Exception;

    List<FlightCabinAncillaryResponse> getAllByFlightIdAndCabinClassIdAndType(
            Long flightId, Long cabinClassId, AncillaryType type) throws Exception;

    FlightCabinAncillaryResponse update(Long id,
            FlightCabinAncillaryRequest request) throws Exception;

    void delete(Long id) throws Exception;

    Double calculateAncillaryPrice(List<Long> ancillaryIds);
}
