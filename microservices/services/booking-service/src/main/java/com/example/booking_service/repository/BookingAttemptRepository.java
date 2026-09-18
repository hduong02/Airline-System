package com.example.booking_service.repository;

import com.example.booking_service.model.BookingAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingAttemptRepository extends JpaRepository<BookingAttempt, Long> {
    Optional<BookingAttempt> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<BookingAttempt> findByBookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BookingAttempt a where a.id = :id")
    Optional<BookingAttempt> findByIdForUpdate(@Param("id") Long id);
}
