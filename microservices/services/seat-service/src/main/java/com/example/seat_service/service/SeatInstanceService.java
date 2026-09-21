package com.example.seat_service.service;

import java.util.List;
import com.example.payload.response.SeatInstanceResponse;


public interface SeatInstanceService {
    List<SeatInstanceResponse> getSelectedSeats(List<Long> seatInstanceIds);

    Double calculateSeatPrice(List<Long> seatInstanceId);

    void reserveBookingSeats(Long bookingId, List<Long> seatInstanceIds);
    
    void confirmBookingSeats(Long bookingId, List<Long> seatInstanceIds);

    void releaseBookingSeats(Long bookingId, List<Long> seatInstanceIds);
}
