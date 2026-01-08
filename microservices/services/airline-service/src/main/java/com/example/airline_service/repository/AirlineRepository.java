package com.example.airline_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.airline_service.model.Airline;
import com.example.enums.AirlineStatus;


import java.util.List;
import java.util.Optional;

public interface AirlineRepository extends JpaRepository<Airline, Long> {

    Optional<Airline> findByOwnerId(Long ownerId);

    List<Airline> findByStatus(AirlineStatus status);

}
