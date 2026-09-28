package com.example.booking_service.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Retained independently of a booking so deleting it cannot reuse an old key. */
@Entity
@Table(name = "booking_attempt", indexes = {
        @Index(name = "idx_booking_attempt_booking", columnList = "booking_id"),
        @Index(name = "idx_booking_attempt_reservation", columnList = "reservation_state, reservation_retry_at")},
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

    @Column(name = "checkout_claim", length = 36)
    private String checkoutClaim;

    @Column(name = "checkout_claim_expires_at")
    private Instant checkoutClaimExpiresAt;

    public enum ReservationState { PENDING, RESERVED, RELEASE_PENDING, RELEASED }

    // Null identifies attempts created before durable reservations were introduced.
    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_state", length = 24)
    private ReservationState reservationState;

    @Column(name = "reservation_seat_ids", columnDefinition = "LONGTEXT")
    private String reservationSeatIds;

    @Column(name = "reservation_claim", length = 36)
    private String reservationClaim;

    @Column(name = "reservation_claim_expires_at")
    private Instant reservationClaimExpiresAt;

    @Column(name = "reservation_retry_at")
    private Instant reservationRetryAt;
}
