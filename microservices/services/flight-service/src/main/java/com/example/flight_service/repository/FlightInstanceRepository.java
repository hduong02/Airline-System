package com.example.flight_service.repository;

import com.example.flight_service.model.FlightInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.time.LocalDateTime;


public interface FlightInstanceRepository extends JpaRepository<FlightInstance, Long> {

    @Query("SELECT fi FROM FlightInstance fi WHERE fi.airlineId = :airlineId" +
            " AND (:departureAirportId IS NULL OR fi.departureAirportId = :departureAirportId)" +
            " AND (:arrivalAirportId IS NULL OR fi.arrivalAirportId = :arrivalAirportId)" +
            " AND (:flightId IS NULL OR fi.flight.id = :flightId)" +
            " AND (:dayStart IS NULL OR fi.departureDateTime >= :dayStart)" +
            " AND (:dayEnd IS NULL OR fi.departureDateTime < :dayEnd)")
    Page<FlightInstance> findByAirlineId(
            @Param("airlineId") Long airlineId,
            @Param("departureAirportId") Long departureAirportId,
            @Param("arrivalAirportId") Long arrivalAirportId,
            @Param("flightId") Long flightId,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd,
            Pageable pageable);

    // Page<FlightInstance> findByStatus(FlightStatus status, Pageable pageable);

    // @Query("SELECT fi FROM FlightInstance fi WHERE fi.departureAirportId = :depId AND fi.arrivalAirportId = :arrId AND fi.departureDateTime >= :fromDate AND fi.departureDateTime <= :toDate AND fi.status = 'SCHEDULED'")
    // List<FlightInstance> searchFlights(
    //         @Param("depId") Long departureAirportId,
    //         @Param("arrId") Long arrivalAirportId,
    //         @Param("fromDate") LocalDateTime fromDate,
    //         @Param("toDate") LocalDateTime toDate);

    // @Query("SELECT fi FROM FlightInstance fi WHERE fi.departureAirportId = :depId AND fi.arrivalAirportId = :arrId AND fi.departureDateTime >= :fromDate AND fi.departureDateTime <= :toDate")
    // Page<FlightInstance> searchFlightsPaged(
    //         @Param("depId") Long departureAirportId,
    //         @Param("arrId") Long arrivalAirportId,
    //         @Param("fromDate") LocalDateTime fromDate,
    //         @Param("toDate") LocalDateTime toDate,
    //         Pageable pageable);

    // @Lock(LockModeType.PESSIMISTIC_WRITE)
    // @Query("SELECT fi FROM FlightInstance fi WHERE fi.id = :id")
    // Optional<FlightInstance> findByIdForUpdate(@Param("id") Long id);

    // Long countByFlightIdAndStatus(Long flightId, FlightStatus status);

    // @Query("SELECT fi FROM FlightInstance fi JOIN FETCH fi.flight WHERE fi.id IN :ids")
    // List<FlightInstance> findAllByIdInWithFlight(@Param("ids") Collection<Long> ids);
}
