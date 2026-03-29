package com.example.seat_service.service;

import java.util.List;

import com.example.enums.SeatAvailabilityStatus;
import com.example.payload.response.SeatInstanceResponse;

public interface SeatInstanceService {

    Double calculateSeatPrice(List<Long> seatInstanceId);
    
    SeatInstanceResponse updateSeatInstanceStatus(long seatInstanceId,
            SeatAvailabilityStatus status);
}
