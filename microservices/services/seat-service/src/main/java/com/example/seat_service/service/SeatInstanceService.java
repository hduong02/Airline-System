package com.example.seat_service.service;

import java.util.List;


public interface SeatInstanceService {

    Double calculateSeatPrice(List<Long> seatInstanceId);
    
    void confirmBookingSeats(Long bookingId, List<Long> seatInstanceIds);

    void releaseBookingSeats(Long bookingId, List<Long> seatInstanceIds);
}
