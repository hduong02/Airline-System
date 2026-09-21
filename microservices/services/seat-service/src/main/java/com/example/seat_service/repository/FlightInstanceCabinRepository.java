package com.example.seat_service.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.seat_service.model.FlightInstanceCabin;
import com.example.enums.CabinClassType;


public interface FlightInstanceCabinRepository extends JpaRepository<
        FlightInstanceCabin, Long> {

    interface BookingCapacity {
        Long getId();
        Long getFlightInstanceId();
        CabinClassType getCabinClassType();
        Integer getAvailableSeats();
    }

    @Query("SELECT c.id AS id, c.flightInstanceId AS flightInstanceId, cc.name AS cabinClassType, "
            + "c.totalSeats - c.bookedSeats AS availableSeats FROM FlightInstanceCabin c JOIN c.cabinClass cc "
            + "WHERE c.flightInstanceId = :flightInstanceId AND cc.name = :cabinClassType")
    java.util.Optional<BookingCapacity> findBookingCapacity(@Param("flightInstanceId") Long flightInstanceId,
            @Param("cabinClassType") CabinClassType cabinClassType);

    Page<FlightInstanceCabin> findByFlightInstanceId(Long flightInstanceId, Pageable pageable);

    FlightInstanceCabin findByFlightInstanceIdAndCabinClassId(Long flightInstanceId, Long cabinClassId);

    java.util.Optional<FlightInstanceCabin> findByFlightInstanceIdAndCabinClass_Name(
            Long flightInstanceId, CabinClassType cabinClassType);

    // Use the current database value, not a cabin cached through an eager seat association.
    // The conditional update locks the row and checks capacity in the same statement.
    @Modifying(flushAutomatically = true)
    @Query("UPDATE FlightInstanceCabin c SET c.bookedSeats = c.bookedSeats + :count "
            + "WHERE c.id = :id AND c.bookedSeats <= c.totalSeats - :count")
    int reserveCapacity(@Param("id") Long id, @Param("count") int count);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE FlightInstanceCabin c SET c.bookedSeats = c.bookedSeats - :count "
            + "WHERE c.id = :id AND c.bookedSeats >= :count")
    int releaseCapacity(@Param("id") Long id, @Param("count") int count);
}
