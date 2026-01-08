package com.example.airline_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.airline_service.model.Aircraft;
import com.example.airline_service.model.Airline;
import com.example.enums.AircraftStatus;

import java.time.LocalDate;
import java.util.Optional;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {

    Optional<Aircraft> findByCode(String code);

    boolean existsByCode(String code);

    List<Aircraft> findByStatus(AircraftStatus status);

    List<Aircraft> findByAirline(Airline airline);

    List<Aircraft> findByAirlineAndStatus(Airline airline, AircraftStatus status);

    List<Aircraft> findByAirlineAndStatusAndIsAvailable(
            Airline airline, AircraftStatus status, Boolean isAvailable);

    List<Aircraft> findByModelContainingIgnoreCase(String model);

    List<Aircraft> findByNextMaintenanceDateBefore(LocalDate date);

    Aircraft findByIdAndAirlineId(Long id, Long airlineId);
}