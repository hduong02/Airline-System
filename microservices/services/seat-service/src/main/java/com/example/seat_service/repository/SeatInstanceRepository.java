package com.example.seat_service.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seat_service.model.SeatInstance;

import java.util.List;
import java.util.Optional;

public interface SeatInstanceRepository extends JpaRepository<SeatInstance, Long> {
    interface BookingSeat {
        Long getId();
        Long getFlightId();
        Long getFlightInstanceId();
        Long getFlightCabinId();
        Double getPrice();
    }

    @Query("SELECT s.id AS id, s.flightId AS flightId, s.flightInstanceId AS flightInstanceId, "
            + "s.flightInstanceCabin.id AS flightCabinId, s.premiumSurcharge AS price "
            + "FROM SeatInstance s WHERE s.id IN :ids")
    List<BookingSeat> findBookingSeats(@Param("ids") List<Long> ids);

    @Query("SELECT si FROM SeatInstance si JOIN FETCH si.seat s "
            + "LEFT JOIN FETCH s.seatMap LEFT JOIN FETCH s.cabinClass "
            + "JOIN FETCH si.flightInstanceCabin WHERE si.id IN :ids")
    List<SeatInstance> findSelectedWithDetails(@Param("ids") List<Long> ids);

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
    // Reservation/confirmation/release need inventory state and the cabin ID only.
    // A fetch graph overrides eager mappings without changing detail-read behavior.
    // Keep the lock on the inventory row; capacity updates lock cabins in ID order.
    @EntityGraph(attributePaths = "id", type = EntityGraph.EntityGraphType.FETCH)
    @Query("SELECT si FROM SeatInstance si WHERE si.id = :seatInstanceId")
    Optional<SeatInstance> findByIdForUpdate(@Param("seatInstanceId") Long seatInstanceId);
}
