package com.example.seat_service.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seat_service.model.SeatInstance;

import java.util.List;
import java.util.Optional;

public interface SeatInstanceRepository extends JpaRepository<SeatInstance, Long> {
    List<SeatInstance> findByFlightId(Long flightId);
    List<SeatInstance> findByFlightScheduleId(Long flightScheduleId);
    List<SeatInstance> findBySeatId(Long seatId);
    List<SeatInstance> findByFlightInstanceCabinId(Long id);

    @Query("SELECT si FROM SeatInstance si WHERE si.flightId = :flightId AND si.status = 'AVAILABLE'")
    List<SeatInstance> findAvailableByFlightId(@Param("flightId") Long flightId);

    @Query("SELECT si FROM SeatInstance si WHERE si.flightId = :flightId AND si.status = 'OCCUPIED'")
    List<SeatInstance> findOccupiedByFlightId(@Param("flightId") Long flightId);

    Long countByFlightId(Long flightId);

    @Query("SELECT COUNT(si) FROM SeatInstance si WHERE si.flightId = :flightId AND si.status = 'AVAILABLE'")
    Long countAvailableByFlightId(@Param("flightId") Long flightId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT si FROM SeatInstance si JOIN FETCH si.seat s JOIN FETCH si.flightInstanceCabin fc WHERE si.id = :seatInstanceId")
    Optional<SeatInstance> findByIdForUpdate(@Param("seatInstanceId") Long seatInstanceId);
}