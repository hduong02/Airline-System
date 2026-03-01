package com.example.payment_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.payment_service.model.Payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    List<Payment> findByBookingIdIn(Collection<Long> bookingIds);

}
