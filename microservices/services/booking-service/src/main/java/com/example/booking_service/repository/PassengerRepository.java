package com.example.booking_service.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.example.booking_service.model.Passenger;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

}
