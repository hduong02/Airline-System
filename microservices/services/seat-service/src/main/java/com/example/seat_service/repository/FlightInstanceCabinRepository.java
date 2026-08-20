package com.example.seat_service.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.example.seat_service.model.FlightInstanceCabin;
import com.example.enums.CabinClassType;


public interface FlightInstanceCabinRepository extends JpaRepository<
        FlightInstanceCabin, Long> {

    Page<FlightInstanceCabin> findByFlightInstanceId(Long flightInstanceId, Pageable pageable);

    FlightInstanceCabin findByFlightInstanceIdAndCabinClassId(Long flightInstanceId, Long cabinClassId);

    java.util.Optional<FlightInstanceCabin> findByFlightInstanceIdAndCabinClass_Name(
            Long flightInstanceId, CabinClassType cabinClassType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT fic FROM FlightInstanceCabin fic WHERE fic.id = :id")
    java.util.Optional<FlightInstanceCabin> findByIdForUpdate(@Param("id") Long id);
}
