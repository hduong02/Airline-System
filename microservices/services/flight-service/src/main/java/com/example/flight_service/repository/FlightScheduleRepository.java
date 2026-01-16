package com.example.flight_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.flight_service.model.FlightSchedule;

import java.util.List;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, Long> {
    
    List<FlightSchedule> findByFlightAirlineId(Long airlineId);

}