package com.example.seat_service.service;

import com.example.payload.response.SeatResponse;

import java.util.List;

public interface SeatService {

    void generateSeats(Long seatMapId) throws Exception;
    SeatResponse updateSeat(Long id, SeatRequest request);
    SeatResponse getSeatById(Long id) throws Exception;
    List<SeatResponse> getAll();

}