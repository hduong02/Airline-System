package com.example.seat_service.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.seat_service.model.FlightInstanceCabin;


public interface FlightInstanceCabinRepository extends JpaRepository<
        FlightInstanceCabin, Long> {

    Page<FlightInstanceCabin> findByFlightInstanceId(Long flightInstanceId, Pageable pageable);

    FlightInstanceCabin findByFlightInstanceIdAndCabinClassId(Long flightInstanceId, Long cabinClassId);
}
