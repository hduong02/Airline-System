package com.example.booking_service.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Retained independently of a booking so deleting it cannot reuse an old key. */
@Entity
@Table(name = "booking_attempt", indexes = @Index(name = "idx_booking_attempt_booking", columnList = "booking_id"),
        uniqueConstraints = @UniqueConstraint(
        name = "uk_booking_attempt_user_key", columnNames = {"user_id", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor
public class BookingAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "payment_request", columnDefinition = "LONGTEXT")
    private String paymentRequest;

    @Column(name = "checkout_response", columnDefinition = "LONGTEXT")
    private String checkoutResponse;
}
