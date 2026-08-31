package com.example.booking_service.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.booking_service.model.Booking;
import com.example.enums.BookingStatus;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import jakarta.persistence.LockModeType;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);
    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id AND b.userId = :userId")
    Optional<Booking> findOwnedByIdForUpdate(@Param("id") Long id,
            @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    List<Booking> findByUserId(Long userId);

    boolean existsByBookingReference(String bookingReference);

    @Query("SELECT b.id FROM Booking b WHERE b.status = :status AND b.holdExpiresAt <= :now ORDER BY b.holdExpiresAt")
    List<Long> findExpiredBookingIds(@Param("status") BookingStatus status, @Param("now") Instant now);

    @Query("""
            SELECT DISTINCT b FROM Booking b
            LEFT JOIN FETCH b.passengers p
            WHERE b.airlineId = :airlineId
            AND (:search IS NULL OR
            LOWER(b.bookingReference) LIKE LOWER(CONCAT('%', :search, '%')) OR
            LOWER(p.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
            LOWER(p.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR
            LOWER(p.email) LIKE LOWER(CONCAT('%', :search, '%')) OR
            LOWER(b.contactInfo.email) LIKE LOWER(CONCAT('%', :search, '%')) OR
            LOWER(b.contactInfo.phone) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:status IS NULL OR b.status = :status)
            AND (:flightInstanceId IS NULL OR b.flightInstanceId = :flightInstanceId)
            """)
    List<Booking> findByAirlineWithFilters(
            @Param("airlineId") Long airlineId,
            @Param("search") String search,
            @Param("status") BookingStatus status,
            @Param("flightInstanceId") Long flightInstanceId,
            Sort sort);
}
