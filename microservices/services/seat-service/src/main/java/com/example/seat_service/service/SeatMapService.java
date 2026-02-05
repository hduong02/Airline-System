package com.example.seat_service.service;

import com.example.payload.request.SeatMapRequest;
import com.example.payload.response.SeatMapResponse;

public interface SeatMapService {

    SeatMapResponse createSeatMap(Long airlineId, SeatMapRequest request) throws Exception;

    SeatMapResponse getSeatMapById(Long id) throws Exception;

    SeatMapResponse getSeatMapsByCabinClass(Long cabinClassId);

    SeatMapResponse updateSeatMap(Long id, SeatMapRequest request) throws Exception;

    void deleteSeatMap(Long id) throws Exception;
}
