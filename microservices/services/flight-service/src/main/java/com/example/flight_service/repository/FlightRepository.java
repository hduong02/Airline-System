package com.example.flight_service.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.flight_service.model.Flight;

import java.util.Optional;


public interface FlightRepository extends JpaRepository<Flight, Long> {


    boolean existsByFlightNumber(String flightNumber);
    boolean existsByFlightNumberAndIdNot(String flightNumber, Long id);

        @Query("""
            SELECT f FROM Flight f
            WHERE f.airlineId = :airlineId
              AND (:depId IS NULL OR f.departureAirportId = :depId)
              AND (:arrId IS NULL OR f.arrivalAirportId = :arrId)
            """)
    Page<Flight> findByAirlineId( @Param("airlineId") Long airlineId,
            @Param("depId") Long departureAirportId,
            @Param("arrId") Long arrivalAirportId,
            Pageable pageable);
    
    Optional<Flight> findByAirlineIdAndId(Long airlineId, Long id);
}
