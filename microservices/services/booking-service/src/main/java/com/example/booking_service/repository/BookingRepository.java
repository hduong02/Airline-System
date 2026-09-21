package com.example.booking_service.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    interface ConfirmationStatusRow {
        Long getBookingId();
        BookingStatus getStatus();
        Long getPaymentId();
        boolean getTicketIssued();
        long getTicketCount();
    }

    // Scalar projection: no booking entity, passenger, or ticket collection is loaded.
    @Query("""
            SELECT b.id AS bookingId, b.status AS status, b.paymentId AS paymentId,
                   b.ticketIssued AS ticketIssued, COUNT(t.id) AS ticketCount
            FROM Booking b LEFT JOIN b.tickets t
            WHERE b.id = :id AND b.userId = :userId
            GROUP BY b.id, b.status, b.paymentId, b.ticketIssued
            """)
    Optional<ConfirmationStatusRow> findConfirmationStatus(@Param("id") Long id, @Param("userId") Long userId);

    Page<Booking> findByUserId(Long userId, Pageable pageable);
    Page<Booking> findByUserIdAndStatus(Long userId, BookingStatus status, Pageable pageable);

    @Query("SELECT DISTINCT b.flightInstanceId FROM Booking b WHERE b.userId = :userId AND b.status <> :excluded")
    List<Long> findHistoryFlightIds(@Param("userId") Long userId, @Param("excluded") BookingStatus excluded);

    @Query("SELECT b FROM Booking b WHERE b.userId = :userId AND b.status <> :excluded AND b.flightInstanceId IN :ids")
    Page<Booking> findHistoryByFlightIds(@Param("userId") Long userId, @Param("excluded") BookingStatus excluded,
            @Param("ids") List<Long> ids, Pageable pageable);

    // EXISTS avoids duplicate bookings and collection fetch joins, so LIMIT/OFFSET
    // and the count both run in the database even when several passengers match.
    @Query("""
            SELECT b FROM Booking b WHERE b.airlineId = :airlineId
            AND (:search IS NULL OR
              LOWER(b.bookingReference) LIKE LOWER(CONCAT('%', :search, '%')) OR
              LOWER(b.contactInfo.email) LIKE LOWER(CONCAT('%', :search, '%')) OR
              LOWER(b.contactInfo.phone) LIKE LOWER(CONCAT('%', :search, '%')) OR
              EXISTS (SELECT p.id FROM Passenger p WHERE p.booking = b AND (
                LOWER(p.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
                LOWER(p.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR
                LOWER(p.email) LIKE LOWER(CONCAT('%', :search, '%')))))
            AND (:status IS NULL OR b.status = :status)
            AND (:flightInstanceId IS NULL OR b.flightInstanceId = :flightInstanceId)
            """)
    Page<Booking> findAirlinePage(@Param("airlineId") Long airlineId,
            @Param("search") String search, @Param("status") BookingStatus status,
            @Param("flightInstanceId") Long flightInstanceId, Pageable pageable);

    interface StatisticsRow {
        Long getId();
        Long getFlightId();
        Instant getBookingDate();
        BookingStatus getStatus();
    }

    @Query("""
            SELECT b.id AS id, b.flightId AS flightId, b.bookingDate AS bookingDate, b.status AS status FROM Booking b
            WHERE b.airlineId = :airlineId AND b.bookingDate >= :start AND b.bookingDate < :end
            """)
    List<StatisticsRow> findStatisticsRows(@Param("airlineId") Long airlineId,
            @Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT b.id FROM Booking b WHERE b.airlineId = :airlineId ORDER BY b.id")
    List<Long> findIdsByAirlineId(@Param("airlineId") Long airlineId);

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
