package com.example.booking_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.example.booking_service.mapper.PassengerMapper;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.PassengerRepository;
import com.example.booking_service.service.PassengerService;
import com.example.payload.request.PassengerRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class PassengerServiceImpl implements PassengerService {

    private final PassengerRepository passengerRepository;

    @Override
    public Passenger createPassenger(PassengerRequest request, Long userId)
            throws Exception {
        Passenger passenger = PassengerMapper.toEntity(request);
        passenger.setPrimaryUserId(userId);
        Passenger saved = passengerRepository.save(passenger);
        return saved;
    }
}
