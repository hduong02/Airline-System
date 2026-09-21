package com.example.payment_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.example.payment_service.model.Payment;
import com.example.payment_service.model.CheckoutVerificationSnapshot;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    @Query("SELECT new com.example.payment_service.model.CheckoutVerificationSnapshot"
            + "(p.id, p.userId, p.status, p.checkoutSessionId) FROM Payment p WHERE p.bookingId = :bookingId")
    Optional<CheckoutVerificationSnapshot> findCheckoutVerificationSnapshot(@Param("bookingId") Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.bookingId = :bookingId")
    Optional<Payment> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    List<Payment> findByBookingIdIn(Collection<Long> bookingIds);

    List<Payment> findByBookingIdInAndUserId(Collection<Long> bookingIds, Long userId);

}
