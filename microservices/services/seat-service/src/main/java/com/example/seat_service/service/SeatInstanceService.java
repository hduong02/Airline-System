package com.example.seat_service.service;

import java.util.List;

public interface SeatInstanceService {

    Double calculateSeatPrice(List<Long> seatInstanceId);
}
