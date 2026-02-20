package com.example.booking_service.service;

import com.example.booking_service.model.Passenger;
import com.example.payload.request.PassengerRequest;

public interface PassengerService {

    Passenger createPassenger(PassengerRequest request, Long userId) throws Exception;
}
