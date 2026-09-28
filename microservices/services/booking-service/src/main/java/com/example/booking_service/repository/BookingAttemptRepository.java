package com.example.booking_service.repository;

import com.example.booking_service.model.BookingAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

public interface BookingAttemptRepository extends JpaRepository<BookingAttempt, Long> {
    Optional<BookingAttempt> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<BookingAttempt> findByBookingId(Long bookingId);

    @Query("""
            select a.id from BookingAttempt a left join Booking b on b.id = a.bookingId
            where a.reservationState is not null
              and a.reservationState <> :released
              and (a.reservationState <> :reserved
                   or b.id is null or b.status = com.example.enums.BookingStatus.CANCELLED
                   or (a.checkoutResponse is null and b.status = com.example.enums.BookingStatus.PENDING
                       and b.holdExpiresAt > :now
                       and (a.checkoutClaimExpiresAt is null or a.checkoutClaimExpiresAt <= :now)))
              and (a.reservationRetryAt is null or a.reservationRetryAt <= :now)
              and (a.reservationClaimExpiresAt is null or a.reservationClaimExpiresAt <= :now)
            order by a.id
            """)
    List<Long> findRecoverableReservationIds(@Param("now") Instant now,
            @Param("reserved") BookingAttempt.ReservationState reserved,
            @Param("released") BookingAttempt.ReservationState released, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BookingAttempt a where a.id = :id")
    Optional<BookingAttempt> findByIdForUpdate(@Param("id") Long id);
}
